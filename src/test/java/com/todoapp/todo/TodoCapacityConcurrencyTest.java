package com.todoapp.todo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.todoapp.support.IntegrationTest;
import com.todoapp.support.TodoSeeder;

import tools.jackson.databind.json.JsonMapper;

/**
 * Story 2.2 AC-4（BE-P7、BE-P8；R-1，AD-10、SM-5）：真端口 + 真连接池 + 真并发（test-design TC-3）。
 *
 * <p><b>类上不加 {@code @Transactional}</b>：测试事务会把请求串行化，测不出竞争（{@code src/test/README.md}「写集成测试」）。
 * HTTP 用 JDK {@link HttpClient}（与 {@code RandomPortFrameworkTest} 一致，零新增依赖），每个请求显式超时。
 *
 * <p>占锁用的常量键直接引用 {@code TodoRepository.CAPACITY_LOCK_KEY}（同包、包级可见）。
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Story 2.2 容量原子校验（并发 / 锁超时）")
class TodoCapacityConcurrencyTest {

    private static final int CONCURRENT = 20;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** AD-10：服务端单请求耗时必须小于前端 5s 超时（这里只断上界，不做性能断言）。 */
    private static final Duration FRONTEND_TIMEOUT = Duration.ofSeconds(5);

    /** 锁超时那次请求的标题标记：500 记 ERROR 堆栈时不得带出标题。 */
    private static final String MARK = "LOGMARK-锁超时-3f8b0";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("[P0] AC-4 BE-P7/BE-P8 499 条时并发 20 个 POST → 恰 1 个 201、19 个 409、终态 500；另一连接占住容量锁时 POST → 500 INTERNAL_ERROR、不新增、< 5s")
    void concurrentCreatesAreAtomic(CapturedOutput output) throws Exception {
        TodoSeeder.seed(jdbc, 499);

        List<Integer> statuses = new ArrayList<>();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
            try {
                CountDownLatch ready = new CountDownLatch(CONCURRENT);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> futures = new ArrayList<>();
                for (int i = 0; i < CONCURRENT; i++) {
                    String title = "并发 #" + i;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return post(http, title).statusCode();
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).as("20 个线程都到起跑线").isTrue();
                go.countDown();
                for (Future<Integer> f : futures) {
                    statuses.add(f.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }

            Map<Integer, Long> byStatus = statuses.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            // BE-P7——本 AC 最先可能失败的断言：恰好 1 个 201
            assertThat(byStatus.getOrDefault(201, 0L)).as("恰好 1 个 201，实际 %s", byStatus).isEqualTo(1L);
            assertThat(byStatus.getOrDefault(409, 0L)).as("其余 19 个 409").isEqualTo(19L);
            assertThat(byStatus.keySet()).as("没有其他状态码").containsOnly(201, 409);
            assertThat(count()).as("终态 500").isEqualTo(500);
            assertThat(jdbc.sql("SELECT count(DISTINCT created_at) FROM todo").query(Long.class).single())
                    .as("created_at 仍全表唯一").isEqualTo(500);

            // BE-P8：回落到 499（本来会成功），另一个连接、另一个事务占住同一把 advisory 锁
            jdbc.sql("DELETE FROM todo WHERE id = (SELECT id FROM todo ORDER BY created_at LIMIT 1)").update();
            assertThat(count()).isEqualTo(499);
            long lockKey = capacityLockKey();
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                        ps.setLong(1, lockKey);
                        ps.execute();
                    }
                    long started = System.nanoTime();
                    HttpResponse<String> blocked = post(http, MARK);
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

                    assertThat(blocked.statusCode()).as("lock_timeout 2s → 500（不是 409）").isEqualTo(500);
                    assertThat(JsonMapper.builder().build().readTree(blocked.body()).at("/error/code").asString())
                            .isEqualTo("INTERNAL_ERROR");
                    assertThat(blocked.body()).doesNotContain("Exception", "SQL", "at com.", MARK);
                    assertThat(elapsed).as("服务端耗时 < 前端 5s 超时").isLessThan(FRONTEND_TIMEOUT);
                    assertThat(count()).as("500 不新增").isEqualTo(499);
                } finally {
                    // 必须释放：否则下一个测试的清表会卡在 lock_timeout 上（README 排错）
                    holder.rollback();
                }
            }
        }

        // 500 记 ERROR 堆栈，但不得带出标题
        assertThat(output.getAll()).doesNotContain(MARK);
    }

    private HttpResponse<String> post(HttpClient http, String title) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/todos"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(TodoCreateApiTest.json(title), UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM todo").query(Long.class).single();
    }

    /** AD-10：常量键只在 {@code TodoRepository} 定义；包级可见供同包测试占锁（story 偏差 2）。 */
    private static long capacityLockKey() {
        return TodoRepository.CAPACITY_LOCK_KEY;
    }
}
