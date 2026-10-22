package com.todoapp.todo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.todoapp.support.IntegrationTest;
import com.todoapp.support.MutableClock;
import com.todoapp.support.TodoSeeder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 2.2 接口层：{@code POST /api/todos}（§11.3、§11.4、AD-7、AD-8、AD-9、契约 {@code CreateTodoRequest} / {@code Todo}）。
 *
 * <ul>
 *   <li>AC-1（BE-P1、BE-G4 同毫秒部分）：201 完整 todo、取时走 {@code TodoService.now()}、{@code max(now, max+1ms)} 置顶；</li>
 *   <li>AC-2（BE-P2/P3/P4/P5）：裁剪、码点长度、U+0000 / 孤立代理项，以及 {@code title-cases.json} 全部 17 条经 HTTP 成立；</li>
 *   <li>AC-3（BE-P6、BE-P10）：满 500 → 409 不新增；直接 SQL 删一条回落后可再新增；</li>
 *   <li>AC-5（BE-X1、BE-P9）：标题纯文本存储；类型错 / 缺 title / 非 JSON → 400 且不新增；请求标题不进日志。</li>
 * </ul>
 * AC-4（并发 / 锁超时）在 {@link TodoCapacityConcurrencyTest}，AC-6（事务边界）在 {@link TodoCreateLayeringTest}。
 *
 * <p>ATDD 红阶段：只经 HTTP 与直接 SQL 访问，不引用本 story 新增的生产类型，整套现在就能编译。
 * 请求体一律用 {@link #json(String)} 生成：非 ASCII 全部写成 JSON 的 {@code \\uXXXX} 转义，
 * 这样孤立代理项能原样到达服务端（直接放进 Java 字符串再经 UTF-8 编码会被替换成 {@code ?}）。
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Story 2.2 POST /api/todos")
class TodoCreateApiTest {

    private static final String UUID_LOWER = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    /** {@link MutableClock#DEFAULT_INSTANT}（.123456）经 {@code now()} 截断到毫秒后的 wire 串。 */
    private static final String NOW_WIRE = "2026-01-15T02:30:00.123Z";

    /** 共享标题用例表，路径相对 todoapp-api basedir（surefire 工作目录）。文件不存在时测试失败，不跳过。 */
    private static final Path TITLE_CASES = Path.of("..", "docs", "contracts", "title-cases.json");

    /** 请求标题里的唯一标记：出现在日志里即说明记录了请求体（Consistency Conventions「日志」）。 */
    private static final String MARK = "LOGMARK-新增-7d41a";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MutableClock clock;

    @Test
    @DisplayName("[P0] AC-1 BE-P1/BE-G4 POST 买牛奶 → 201 完整 todo（completed=false、createdAt=updatedAt、小写 UUID），同毫秒与时钟回拨时取 max+1ms 并置顶")
    void createsPinnedTodo() throws Exception {
        // When：清单为空（基座已清表）、时钟冻结在 .123456
        MvcResult created = mvc.perform(postJson(json("买牛奶")))
                // 本 AC 最先可能失败的断言：201
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/json")))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.title").value("买牛奶"))
                .andExpect(jsonPath("$.completed").value(false))
                // 取时走 TodoService.now()：.123456 截断为 .123（不是四舍五入成 .124）
                .andExpect(jsonPath("$.createdAt").value(NOW_WIRE))
                .andExpect(jsonPath("$.updatedAt").value(NOW_WIRE))
                .andExpect(jsonPath("$.id").value(matchesPattern(UUID_LOWER)))
                .andReturn();
        String id = JsonPath.read(body(created), "$.id");

        // 响应用 RETURNING 构造：id 就是库里那一行
        assertThat(jdbc.sql("SELECT title FROM todo WHERE id = CAST(:id AS uuid)").param("id", id)
                .query(String.class).single()).isEqualTo("买牛奶");
        assertThat(count()).isEqualTo(1);

        // BE-G4：时钟不动，连续两次新增 → max + 1ms 严格递增
        mvc.perform(postJson(json("第二条"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value("2026-01-15T02:30:00.124Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-15T02:30:00.124Z"));
        mvc.perform(postJson(json("第三条"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value("2026-01-15T02:30:00.125Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-15T02:30:00.125Z"));

        // BE-G4：时钟回拨到比现有 max 更早 → 仍取 max + 1ms（不撞唯一索引、不触发 updated_after 约束 → 不是 500）
        clock.setInstant(MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(1)));
        mvc.perform(postJson(json("回拨后"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value("2026-01-15T02:30:00.126Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-15T02:30:00.126Z"));

        // GET 置顶：后建在前
        mvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].title").value("回拨后"))
                .andExpect(jsonPath("$.items[1].title").value("第三条"))
                .andExpect(jsonPath("$.items[2].title").value("第二条"))
                .andExpect(jsonPath("$.items[3].id").value(id));
    }

    @Test
    @DisplayName("[P0] AC-1 BE-G4 已有数据晚于时钟（播种未来时间）时新增取 max+1ms 并置顶；时钟晚于 max 时取 now")
    void createdAtFollowsLatestWhenSeedIsInFuture() throws Exception {
        // Given：一条比冻结时钟（.123）晚 1 小时的数据
        Instant future = Instant.parse("2026-01-15T03:30:00.500Z");
        TodoSeeder.insert(jdbc, TodoSeeder.Row.created("未来的一条", false, future));

        // When / Then：now < max → created_at = max + 1ms，updated_at 同值
        mvc.perform(postJson(json("晚于未来")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value("2026-01-15T03:30:00.501Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-15T03:30:00.501Z"));

        // 时钟走到 max 之后 → 直接取 now（截断到毫秒）
        clock.setInstant(Instant.parse("2026-01-15T04:00:00.777999Z"));
        mvc.perform(postJson(json("时钟领先")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value("2026-01-15T04:00:00.777Z"));

        mvc.perform(get("/api/todos"))
                .andExpect(jsonPath("$.items[0].title").value("时钟领先"))
                .andExpect(jsonPath("$.items[1].title").value("晚于未来"))
                .andExpect(jsonPath("$.items[2].title").value("未来的一条"));
    }

    @Test
    @DisplayName("[P0] AC-2 BE-P2/BE-P3/BE-P4/BE-P5 标题裁剪后存储、空白 / 超 200 码点 / U+0000 / 孤立代理项 → 400 且不新增，title-cases.json 17 条经 HTTP 成立")
    void titleTrimAndCodePointRules() throws Exception {
        // BE-P2——本 AC 最先可能失败的断言：裁剪后合法 → 201
        MvcResult hi = mvc.perform(postJson(json("  hi  ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("hi"))
                .andReturn();
        assertThat(storedTitle(JsonPath.read(body(hi), "$.id"))).isEqualTo("hi");

        // BE-P3：空白 / 空串 → 400 且不新增
        for (String blank : List.of("   ", "", "\u3000\t \u00a0")) {
            expectRejected(json(blank));
        }

        // BE-P4：码点计数（代理对计 1）、不规范化
        expectStoredCodePoints(json("字".repeat(200)), 200);
        expectStoredCodePoints(json("😀".repeat(200)), 200);
        expectRejected(json("字".repeat(201)));
        expectRejected(json("😀".repeat(201)));

        // BE-P5：U+0000、孤立高代理项 → 400 且不新增（请求体里是 JSON 转义 \u0000 / \ud800）
        expectRejected(json("a\u0000b"));
        expectRejected(json("a\ud800b"));

        // AD-7：共享用例表全部 17 条在后端成立
        JsonNode cases = JsonMapper.builder().build().readTree(Files.readString(TITLE_CASES, UTF_8)).get("cases");
        assertThat(cases.size()).as("title-cases.json 共 17 条").isEqualTo(17);
        List<String> checked = new ArrayList<>();
        for (JsonNode c : cases) {
            String input = input(c);
            if (c.get("valid").asBoolean()) {
                // 有 trimmed 用它；repeat 用例的期望是 unit × count（pad 两端被裁掉）
                String expected = c.has("trimmed")
                        ? c.get("trimmed").asString()
                        : c.get("repeat").get("unit").asString().repeat(c.get("repeat").get("count").asInt());
                MvcResult r = mvc.perform(postJson(json(input))).andExpect(status().isCreated()).andReturn();
                assertThat(storedTitle(JsonPath.read(body(r), "$.id")))
                        .as("用例 %s 存裁剪后的值", c.get("name").asString()).isEqualTo(expected);
            } else {
                expectRejected(json(input));
            }
            checked.add(c.get("name").asString());
        }
        assertThat(checked).hasSize(17);
    }

    @Test
    @DisplayName("[P0] AC-3 BE-P6/BE-P10 恰 500 条时 POST → 409 LIMIT_EXCEEDED 不新增，直接 SQL 删一条回落后再 POST → 201")
    void capacityFullRejectsThenRecovers() throws Exception {
        TodoSeeder.seed(jdbc, 500);

        mvc.perform(postJson(json("第 501 条")))
                // 本 AC 最先可能失败的断言：409
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.length()").value(1));
        assertThat(count()).as("409 不新增").isEqualTo(500);

        // BE-P10：删除接口在 Story 3.2，这里用直接 SQL 让总数回落到 499
        jdbc.sql("DELETE FROM todo WHERE id = (SELECT id FROM todo ORDER BY created_at LIMIT 1)").update();
        assertThat(count()).isEqualTo(499);

        mvc.perform(postJson(json("回落后可再新增"))).andExpect(status().isCreated());
        assertThat(count()).isEqualTo(500);
    }

    @Test
    @DisplayName("[P0] AC-5 BE-X1/BE-P9/BE-E2 标题按纯文本原样存储，类型错 / 缺 title / 畸形 JSON / 非 JSON Content-Type → 400 VALIDATION_ERROR 且不新增，不信任客户端字段")
    void strictValidationAndPlainTextStorage(CapturedOutput output) throws Exception {
        String script = "<script>alert(1)</script>";

        // BE-X1——本 AC 最先可能失败的断言：含标签的标题 201 且原样存储
        // （放在 400 矩阵之前：2.1 时 POST 无映射，「方法不支持」恰好也映射为 400 VALIDATION_ERROR，
        //   400 分支在实现前就会通过，不能作为本叶子的红判定）
        MvcResult r = mvc.perform(postJson(json(script)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(script))
                .andReturn();
        assertThat(storedTitle(JsonPath.read(body(r), "$.id"))).isEqualTo(script);
        assertThat(count()).isEqualTo(1);

        // BE-P9：严格校验，每条都 400 VALIDATION_ERROR 且不新增
        List<MockHttpServletRequestBuilder> bad = List.of(
                postJson("{\"title\":123}"),
                postJson("{\"title\":true}"),
                postJson("{\"title\":null}"),
                postJson("{}"),
                postJson("{\"other\":\"x\"}"),
                postJson(""),
                postJson("{\"title\":\"" + MARK),
                post("/api/todos").contentType(MediaType.TEXT_PLAIN).content("{\"title\":\"" + MARK + "\"}"));
        for (MockHttpServletRequestBuilder request : bad) {
            mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.length()").value(2));
        }
        assertThat(count()).as("400 一律不新增").isEqualTo(1);

        // AD-8：未知字段一律忽略，id / completed / 时间不信任客户端
        mvc.perform(postJson("{\"title\":\"x\",\"completed\":true,\"id\":\"00000000-0000-0000-0000-000000000000\","
                        + "\"createdAt\":\"2000-01-01T00:00:00.000Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.id").value(not("00000000-0000-0000-0000-000000000000")))
                .andExpect(jsonPath("$.createdAt").value("2026-01-15T02:30:00.124Z"));

        // 日志不记录请求体（标题）
        assertThat(output.getAll()).doesNotContain(MARK);
    }

    // —— helpers ——

    private void expectRejected(String body) throws Exception {
        long before = count();
        mvc.perform(postJson(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(count()).as("400 不新增：%s", body).isEqualTo(before);
    }

    private void expectStoredCodePoints(String body, int codePoints) throws Exception {
        MvcResult r = mvc.perform(postJson(body)).andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(body(r), "$.id");
        assertThat(jdbc.sql("SELECT char_length(title) FROM todo WHERE id = CAST(:id AS uuid)").param("id", id)
                .query(Integer.class).single()).isEqualTo(codePoints);
    }

    private String storedTitle(String id) {
        return jdbc.sql("SELECT title FROM todo WHERE id = CAST(:id AS uuid)").param("id", id)
                .query(String.class).single();
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM todo").query(Long.class).single();
    }

    private static MockHttpServletRequestBuilder postJson(String body) {
        return post("/api/todos").contentType(MediaType.APPLICATION_JSON).content(body.getBytes(UTF_8));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(UTF_8);
    }

    /** 用例输入：{@code input}，或 {@code repeat.unit × repeat.count}（有 {@code pad} 时两端各加一份）。 */
    private static String input(JsonNode c) {
        if (c.has("input")) {
            return c.get("input").asString();
        }
        JsonNode repeat = c.get("repeat");
        String core = repeat.get("unit").asString().repeat(repeat.get("count").asInt());
        String pad = c.has("pad") ? c.get("pad").asString() : "";
        return pad + core + pad;
    }

    /**
     * {@code {"title":"…"}}：可打印 ASCII 之外的每个 UTF-16 单元（含孤立代理项）、{@code "} 与 {@code \} 都写成 {@code \\uXXXX}。
     * 只用于构造请求体，不涉及被测逻辑。
     */
    static String json(String title) {
        StringBuilder sb = new StringBuilder("{\"title\":\"");
        for (char ch : title.toCharArray()) {
            if (ch >= 0x20 && ch <= 0x7e && ch != '"' && ch != '\\') {
                sb.append(ch);
            } else {
                sb.append(String.format("\\u%04x", (int) ch));
            }
        }
        return sb.append("\"}").toString();
    }
}
