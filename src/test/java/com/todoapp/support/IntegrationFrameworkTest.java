package com.todoapp.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/**
 * INF-B2：后端测试基座自检。证明 {@link IntegrationTest} 提供的每一项保证都真的成立，
 * 而不是只证明「上下文能起来」。业务接口的测试由 BE-1..BE-4 在 {@code com.todoapp.todo} 下追加。
 */
@IntegrationTest
@DisplayName("INF-B2 后端集成测试基座")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegrationFrameworkTest {

    private static final String PROBE_TABLE = "framework_probe";
    private static final String SQL_SEED_TABLE = "framework_sql_seed_probe";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private Clock injectedClock;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DatabaseCleaner cleaner;

    @Autowired
    private Environment env;

    @Test
    @DisplayName("连接的是随机端口的测试库，不是本地开发库 54329 或 brew 5432")
    void connectsToIsolatedTestDatabase() {
        int port = cleaner.connectedPort();

        assertThat(port).isEqualTo(TestDatabase.get().getPort());
        assertThat(TestDatabase.FORBIDDEN_PORTS).doesNotContain(port);
    }

    @Test
    @DisplayName("不激活 local profile（也不激活任何 profile）")
    void activatesNoProfile() {
        assertThat(env.getActiveProfiles()).isEmpty();
    }

    @Test
    @DisplayName("测试库是 PostgreSQL 18.6，server_encoding = UTF8")
    void databaseVersionAndEncoding() {
        String version = jdbc.sql("SHOW server_version").query(String.class).single();
        String encoding = jdbc.sql("SHOW server_encoding").query(String.class).single();

        assertThat(version).isEqualTo("18.6");
        assertThat(encoding).isEqualTo("UTF8");
    }

    @Test
    @DisplayName("注入的 Clock 就是冻结的 MutableClock，时间只随测试显式推进")
    void injectedClockIsFrozenAndControllable() {
        assertThat(injectedClock).isSameAs(clock);
        assertThat(injectedClock.instant()).isEqualTo(MutableClock.DEFAULT_INSTANT);

        clock.advance(Duration.ofMinutes(5));

        assertThat(injectedClock.instant()).isEqualTo(MutableClock.DEFAULT_INSTANT.plus(Duration.ofMinutes(5)));
    }

    @Test
    @Order(1)
    @DisplayName("清表与时钟复位（1/2）：本测试留下一行数据并推进时钟")
    void leavesDataAndAdvancedClockBehind() {
        jdbc.sql("CREATE TABLE IF NOT EXISTS " + PROBE_TABLE + " (id int PRIMARY KEY)").update();
        jdbc.sql("INSERT INTO " + PROBE_TABLE + " (id) VALUES (1)").update();
        clock.advance(Duration.ofHours(1));

        assertThat(jdbc.sql("SELECT count(*) FROM " + PROBE_TABLE).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @Order(2)
    @DisplayName("清表与时钟复位（2/2）：下一个测试开始时数据已清空、时钟已复位（ResetTestStateListener 已挂上）")
    void startsWithCleanTableAndResetClock() {
        // 单独运行本方法时表还不存在；顺序运行时表已被 ResetTestStateListener 清空
        jdbc.sql("CREATE TABLE IF NOT EXISTS " + PROBE_TABLE + " (id int PRIMARY KEY)").update();
        try {
            assertThat(jdbc.sql("SELECT count(*) FROM " + PROBE_TABLE).query(Long.class).single()).isZero();
            assertThat(clock.instant()).isEqualTo(MutableClock.DEFAULT_INSTANT);
        } finally {
            jdbc.sql("DROP TABLE IF EXISTS " + PROBE_TABLE).update();
        }
    }

    @Test
    @Sql(statements = {
            "CREATE TABLE IF NOT EXISTS " + SQL_SEED_TABLE + " (id int PRIMARY KEY)",
            "INSERT INTO " + SQL_SEED_TABLE + " (id) VALUES (1), (2)"})
    @DisplayName("@Sql 播种的数据不会被测试前清表抹掉（清表先于 @Sql 执行）")
    void sqlSeedDataSurvivesReset() {
        try {
            assertThat(jdbc.sql("SELECT count(*) FROM " + SQL_SEED_TABLE).query(Long.class).single()).isEqualTo(2L);
        } finally {
            jdbc.sql("DROP TABLE IF EXISTS " + SQL_SEED_TABLE).update();
        }
    }

    @Test
    @DisplayName("MockMvc 已接到真实的 DispatcherServlet：未映射的 /api 路由返回 404")
    void mockMvcDispatchesThroughSpringMvc() throws Exception {
        mvc.perform(get("/api/__framework_probe__")).andExpect(status().isNotFound());
    }
}
