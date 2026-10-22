package com.todoapp.todo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.todoapp.support.IntegrationTest;
import com.todoapp.support.MutableClock;
import com.todoapp.support.TodoSeeder;

import tools.jackson.databind.node.ObjectNode;

/**
 * Story 3.1 接口层：{@code PATCH /api/todos/{id}}（FR-5、FR-6、§11.2、§11.3、§11.4、AD-7、AD-8、AD-9、AD-11、
 * 契约 {@code updateTodo} / {@code UpdateTodoRequest}）。
 *
 * <ul>
 *   <li>AC-1（BE-U1、BE-U2）：切换 completed → 200、updatedAt 更新；值未变 → updatedAt 不更新；</li>
 *   <li>AC-2（BE-U3、BE-X1）：改标题存裁剪后的值、createdAt 与排序不变；两字段同时改；未知字段忽略；纯文本存储；</li>
 *   <li>AC-3（BE-U4）：空 / 空白 / 超 200 码点 / U+0000 / 孤立代理项 → 400 不写入；请求标题不进日志；</li>
 *   <li>AC-4（BE-U5、BE-U6）：{@code {}}、只含未知字段、显式 null、类型不符、框架层坏请求体 → 400 不改数据；</li>
 *   <li>AC-5（BE-U7、BE-U8）：格式合法但不存在、格式非法（含大写 / 去连字符）的 id → 404，不是 400；</li>
 *   <li>AC-6（BE-U9、BE-S2）：时钟回拨时 {@code GREATEST(:now, created_at)} 保证不 500；update 是单一事务入口。</li>
 * </ul>
 *
 * <p>ATDD 红阶段：只经 HTTP、直接 SQL 与反射访问，不在编译期引用本 story 新增的生产类型
 * （{@code TodoIds}、{@code TodoService.update}），整套在实现前就能编译。红阶段的跳过注解
 * 已在 5.3 完成 T1–T4 后全部去掉（断言未改）。
 *
 * <p>请求体里的标题一律用 {@link #titleJson(String)} 生成：非 ASCII 写成 JSON 的 {@code \\uXXXX} 转义，
 * 孤立代理项才能原样到达服务端（同 {@code TodoCreateApiTest#json}，这里私有复制一份，不改 2.2 的文件）。
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Story 3.1 PATCH /api/todos/{id}")
class TodoUpdateApiTest {

    private static final String PKG = "com.todoapp.todo.";

    /** {@link MutableClock#DEFAULT_INSTANT}（.123456）经 {@code TodoService.now()} 截断后的 wire 串。 */
    private static final String NOW_WIRE = "2026-01-15T02:30:00.123Z";

    /** {@link TodoSeeder#DEFAULT_BASE}（时钟截断值前一天）的 wire 串。 */
    private static final String BASE_WIRE = "2026-01-14T02:30:00.123Z";

    /** 请求标题里的唯一标记：出现在日志里即说明记录了请求体（Consistency Conventions「日志」）。 */
    private static final String MARK = "LOGMARK-改标题-3e9c1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MutableClock clock;

    // —— AC-1 ——

    @Test
    @DisplayName("[P0] AC-1 BE-U1/BE-U2 PATCH {\"completed\":true} → 200 完整 todo、completed=true、title 不变、updatedAt 取 now()；值未变再 PATCH → updatedAt 不更新")
    void togglesCompletedAndIsIdempotent() throws Exception {
        UUID id = TodoSeeder.insert(jdbc, TodoSeeder.Row.created("买牛奶", false, TodoSeeder.DEFAULT_BASE));

        mvc.perform(patchJson(id.toString(), "{\"completed\":true}"))
                // 本 AC 最先可能失败的断言：200
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/json")))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("买牛奶"))
                .andExpect(jsonPath("$.createdAt").value(BASE_WIRE))
                // 值变化 → updatedAt 取 TodoService.now()：.123456 截断为 .123（不是四舍五入）
                .andExpect(jsonPath("$.updatedAt").value(NOW_WIRE));
        assertThat(row(id)).as("库里那一行与响应一致").isEqualTo("买牛奶|true|" + NOW_WIRE);

        // BE-U2：时钟前进后再发同值 → 200，updatedAt 保持上一次的值（不是新时钟值）
        clock.advance(Duration.ofMinutes(5));
        mvc.perform(patchJson(id.toString(), "{\"completed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.updatedAt").value(NOW_WIRE));
        // 标题同值（含裁剪后同值）也不更新 updatedAt（变更判定用裁剪后的值，AD-7）
        mvc.perform(patchJson(id.toString(), titleJson("买牛奶")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").value(NOW_WIRE));
        mvc.perform(patchJson(id.toString(), titleJson("  买牛奶  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("买牛奶"))
                .andExpect(jsonPath("$.updatedAt").value(NOW_WIRE));
        assertThat(row(id)).isEqualTo("买牛奶|true|" + NOW_WIRE);

        // 再勾一次恢复未完成 → updatedAt 更新为新的时钟值
        mvc.perform(patchJson(id.toString(), "{\"completed\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.createdAt").value(BASE_WIRE))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-15T02:35:00.123Z"));
        assertThat(row(id)).isEqualTo("买牛奶|false|2026-01-15T02:35:00.123Z");
    }

    @Test
    @DisplayName("[P0] BE-U2 反例：标题改成另一个值再改回原值 → 两次值都真的变了，updatedAt 两次都更新")
    void changeThenRevertUpdatesTimestampBothTimes() throws Exception {
        UUID id = TodoSeeder.insert(jdbc, TodoSeeder.Row.created("原值", false, TodoSeeder.DEFAULT_BASE));

        mvc.perform(patchJson(id.toString(), titleJson("另一个值")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("另一个值"))
                .andExpect(jsonPath("$.updatedAt").value(NOW_WIRE));

        clock.advance(Duration.ofMinutes(3));
        mvc.perform(patchJson(id.toString(), titleJson("原值")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("原值"))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-15T02:33:00.123Z"));
        assertThat(row(id)).isEqualTo("原值|false|2026-01-15T02:33:00.123Z");
    }

    // —— AC-2 ——

    @Test
    @DisplayName("[P0] AC-2 BE-U3/BE-X1 PATCH {\"title\":\"  新标题  \"} → 200 存裁剪后的值、updatedAt 更新、createdAt 与排序不变；200 码点合法、两字段同时改、未知字段忽略、标题纯文本存储")
    void updatesTrimmedTitleKeepingCreatedAtAndOrder() throws Exception {
        // Given：3 条（#0 最旧、#2 最新），改中间那条 #1（播种为已完成，createdAt = BASE + 1ms）
        List<UUID> ids = TodoSeeder.seed(jdbc, 3);
        UUID middle = ids.get(1);

        mvc.perform(patchJson(middle.toString(), titleJson("  新标题  ")))
                // 本 AC 最先可能失败的断言：200
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新标题"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.createdAt").value("2026-01-14T02:30:00.124Z"))
                .andExpect(jsonPath("$.updatedAt").value(NOW_WIRE));
        assertThat(row(middle)).isEqualTo("新标题|true|" + NOW_WIRE);

        // 排序只看 createdAt：改标题后各条相对顺序与 PATCH 前完全一致
        mvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(ids.get(2).toString()))
                .andExpect(jsonPath("$.items[1].id").value(middle.toString()))
                .andExpect(jsonPath("$.items[1].title").value("新标题"))
                .andExpect(jsonPath("$.items[2].id").value(ids.get(0).toString()));

        // 码点计数（代理对计 1）：恰 200 码点合法
        UUID oldest = ids.get(0);
        mvc.perform(patchJson(oldest.toString(), titleJson("字".repeat(200)))).andExpect(status().isOk());
        assertThat(codePoints(oldest)).isEqualTo(200);
        mvc.perform(patchJson(oldest.toString(), titleJson("😀".repeat(200)))).andExpect(status().isOk());
        assertThat(codePoints(oldest)).isEqualTo(200);

        // 两字段同时改 → 两者都改、updatedAt 更新
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(patchJson(oldest.toString(), "{\"title\":\"新\",\"completed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-15T02:31:00.123Z"));

        // 已知 + 未知字段 → 200，未知字段被忽略（AD-8）
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(patchJson(oldest.toString(), "{\"completed\":false,\"foo\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.title").value("新"))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-15T02:32:00.123Z"))
                .andExpect(jsonPath("$.foo").doesNotExist());

        // BE-X1（PATCH 侧，NFR-XSS）：含标签的标题原样存储，不转义、不剥标签
        String script = "<script>alert(1)</script>";
        mvc.perform(patchJson(oldest.toString(), titleJson(script)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(script));
        assertThat(title(oldest)).isEqualTo(script);
        assertThat(count()).isEqualTo(3);
    }

    // —— AC-3 ——

    @Test
    @DisplayName("[P0] AC-3 BE-U4 PATCH 标题裁剪后为空、超 200 码点、含 U+0000 / 孤立代理项 → 400 VALIDATION_ERROR，该行不变，请求标题不进日志")
    void rejectsBlankOrOverlongTitle(CapturedOutput output) throws Exception {
        UUID id = TodoSeeder.insert(jdbc, TodoSeeder.Row.created("原标题", false, TodoSeeder.DEFAULT_BASE));
        String before = row(id);

        mvc.perform(patchJson(id.toString(), titleJson("")))
                // 本 AC 最先可能失败的断言：400
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.length()").value(2));
        assertThat(row(id)).as("空标题不写入").isEqualTo(before);

        List<String> invalid = List.of(
                "   ",
                "\u3000\t \u00a0",
                "字".repeat(201),
                "😀".repeat(201),
                "a\u0000b",
                "a\ud800b",
                MARK + "字".repeat(201));
        for (String t : invalid) {
            expectError(patchJson(id.toString(), titleJson(t)), 400, "VALIDATION_ERROR");
            assertThat(row(id)).as("400 不写入：%s", t.length() > 20 ? t.substring(0, 20) + "…" : t).isEqualTo(before);
        }
        assertThat(count()).isEqualTo(1);

        // 日志不记录请求体（标题）
        assertThat(output.getAll()).doesNotContain(MARK);
    }

    // —— AC-4 ——

    @Test
    @DisplayName("[P0] AC-4 BE-U5/BE-U6 PATCH 空体 {}、只含未知字段、显式 null、类型不符、畸形 / 非对象 JSON、非 JSON Content-Type → 400 VALIDATION_ERROR 不改数据；入参以 ObjectNode 接收")
    void rejectsEmptyNullOrMistypedBody() throws Exception {
        UUID id = TodoSeeder.insert(jdbc, TodoSeeder.Row.created("不该被改", false, TodoSeeder.DEFAULT_BASE));
        String before = row(id);
        String path = id.toString();

        mvc.perform(patchJson(path, "{}"))
                // 本 AC 最先可能失败的断言：400
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.length()").value(2));
        assertThat(row(id)).as("{} 不改数据").isEqualTo(before);

        List<MockHttpServletRequestBuilder> bad = List.of(
                // BE-U5：只含未知字段（大小写不同 = 未知字段）
                patchJson(path, "{\"foo\":\"x\"}"),
                patchJson(path, "{\"Title\":\"x\",\"COMPLETED\":true}"),
                // BE-U6：显式 null（含合法字段旁的 null，证明不会「部分写入」）
                patchJson(path, "{\"title\":null}"),
                patchJson(path, "{\"completed\":null}"),
                patchJson(path, "{\"title\":\"x\",\"completed\":null}"),
                patchJson(path, "{\"completed\":true,\"title\":null}"),
                // BE-U6：类型不符（ObjectNode 树不受 JacksonConfig 强转限制，必须在 service 判节点类型）
                patchJson(path, "{\"title\":123}"),
                patchJson(path, "{\"title\":true}"),
                patchJson(path, "{\"title\":[\"x\"]}"),
                patchJson(path, "{\"title\":{\"a\":1}}"),
                patchJson(path, "{\"completed\":\"true\"}"),
                patchJson(path, "{\"completed\":1}"),
                patchJson(path, "{\"completed\":0}"),
                patchJson(path, "{\"title\":\"x\",\"completed\":\"false\"}"),
                // 框架层（AD-8 映射表）：空请求体、畸形 JSON、非对象 JSON、非 JSON Content-Type
                patchJson(path, ""),
                patchJson(path, "{\"completed\":"),
                patchJson(path, "[]"),
                patchJson(path, "\"x\""),
                patchJson(path, "123"),
                patchJson(path, "null"),
                patch("/api/todos/{id}", path).contentType(MediaType.TEXT_PLAIN).content("{\"completed\":true}"));
        for (MockHttpServletRequestBuilder request : bad) {
            expectError(request, 400, "VALIDATION_ERROR");
            assertThat(row(id)).as("400 不改数据").isEqualTo(before);
        }
        assertThat(count()).isEqualTo(1);

        // AD-8：PATCH 入参是 ObjectNode（禁 record / Optional 作 PATCH DTO），dto 包里没有 PATCH 请求 record
        Method update = patchMapping();
        assertThat(update.getParameterTypes()).as("PATCH 参数恰为 (String id, ObjectNode body)")
                .containsExactly(String.class, ObjectNode.class);
        assertThat(classExists(PKG + "dto.UpdateTodoRequest")).as("不新建 PATCH 请求 DTO").isFalse();
        assertThat(classExists(PKG + "dto.PatchTodoRequest")).as("不新建 PATCH 请求 DTO").isFalse();
    }

    // —— AC-5 ——

    @Test
    @DisplayName("[P0] AC-5 BE-U7/BE-U8 PATCH 格式合法但不存在的 id、格式非法的 id（abc、1-1-1-1-1、大写、去连字符、35/37 位、非十六进制）→ 404 NOT_FOUND 不是 400，数据不变；路径 id 以 String 接收")
    void unknownOrMalformedIdIsNotFound() throws Exception {
        UUID id = TodoSeeder.insert(jdbc, TodoSeeder.Row.created("待查", false, TodoSeeder.DEFAULT_BASE));
        String lower = id.toString();

        // 对照——本 AC 最先可能失败的断言：同一行的规范小写 id → 200。
        // 实现前 /api/todos/{单段} 没有路由，下面所有 404 今天就会成立（来自「无路由」）；
        // 先证明路由存在，后面的 404 才是 id 解析 / UPDATE 无行给出的。
        mvc.perform(patchJson(lower, "{\"completed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(lower));
        String before = row(id);

        List<String> notFound = List.of(
                // BE-U7：格式合法但不存在
                "0b9a7c1e-1d2f-4a3b-8c4d-5e6f7a8b9c0d",
                // BE-U8：格式非法（不得 400、不泄露 id 形态）
                "abc",
                "1-1-1-1-1",
                lower.toUpperCase(Locale.ROOT),
                mixedCase(lower),
                lower.replace("-", ""),
                lower.substring(1),
                lower + "0",
                lower.substring(0, 35) + "g");
        for (String raw : notFound) {
            expectError(patchJson(raw, "{\"completed\":false}"), 404, "NOT_FOUND");
            assertThat(row(id)).as("404 不改数据：%s", raw).isEqualTo(before);
        }
        assertThat(count()).isEqualTo(1);

        // AD-8：路径 id 以 String 接收（禁 @PathVariable UUID），controller 任何方法参数里都没有 UUID
        Method update = patchMapping();
        assertThat(update.getParameterTypes()[0]).isEqualTo(String.class);
        assertThat(update.getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
        assertThat(Arrays.stream(TodoController.class.getDeclaredMethods())
                .flatMap(m -> Arrays.stream(m.getParameterTypes())))
                .as("controller 方法参数不出现 java.util.UUID").doesNotContain(UUID.class);

        // TodoIds：final、只有私有构造器、非 Spring 组件、方法全为 static、parse(String) 返回 UUID（3.2 DELETE 复用）
        Class<?> ids = load("TodoIds");
        assertThat(Modifier.isFinal(ids.getModifiers())).as("TodoIds 是 final 类").isTrue();
        assertThat(AnnotatedElementUtils.hasAnnotation(ids, Component.class)).as("TodoIds 不是 Spring 组件").isFalse();
        for (Constructor<?> c : ids.getDeclaredConstructors()) {
            assertThat(Modifier.isPrivate(c.getModifiers())).as("TodoIds 只有私有构造器").isTrue();
        }
        assertThat(Arrays.stream(ids.getDeclaredMethods()).filter(m -> !m.isSynthetic()))
                .as("TodoIds 的方法全为 static").allMatch(m -> Modifier.isStatic(m.getModifiers()));
        assertThat(ids.getDeclaredMethod("parse", String.class).getReturnType()).isEqualTo(UUID.class);
    }

    // —— AC-6 ——

    @Test
    @DisplayName("[P1] AC-6 BE-U9/BE-S2 时钟回拨（created_at 晚于当前时钟）时 PATCH → 200 不是 500，updatedAt = createdAt（GREATEST），库里 updated_at >= created_at；update 是单一 @Transactional 入口、默认 READ COMMITTED")
    void clockRollbackKeepsUpdatedAtNotBeforeCreatedAt() throws Exception {
        // Given：created_at = 时钟截断值 + 1 天（模拟时钟回拨）
        Instant future = MutableClock.DEFAULT_INSTANT.truncatedTo(ChronoUnit.MILLIS).plus(Duration.ofDays(1));
        String futureWire = "2026-01-16T02:30:00.123Z";
        UUID id = TodoSeeder.insert(jdbc, TodoSeeder.Row.created("来自未来", false, future));

        mvc.perform(patchJson(id.toString(), "{\"completed\":true}"))
                // 本 AC 最先可能失败的断言：200（没有 GREATEST 会触发 todo_updated_after_chk → 500）
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.createdAt").value(futureWire))
                .andExpect(jsonPath("$.updatedAt").value(futureWire));
        assertThat(row(id)).isEqualTo("来自未来|true|" + futureWire);
        assertThat(jdbc.sql("SELECT updated_at >= created_at FROM todo WHERE id = :id").param("id", id)
                .query(Boolean.class).single()).isTrue();

        // 改标题同理
        mvc.perform(patchJson(id.toString(), titleJson("回拨后改名")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("回拨后改名"))
                .andExpect(jsonPath("$.updatedAt").value(futureWire));

        // AD-6 / BE-S2：TodoService.update(String, ObjectNode) 是 public、@Transactional 默认属性、返回领域 record
        Method update = publicMethod(TodoService.class, "update", String.class, ObjectNode.class);
        Transactional tx = AnnotatedElementUtils.findMergedAnnotation(update, Transactional.class);
        assertThat(tx).as("TodoService.update 带 @Transactional").isNotNull();
        assertThat(tx.readOnly()).as("写方法不是 readOnly").isFalse();
        assertThat(tx.isolation()).as("隔离级别用默认 READ COMMITTED，不调高").isEqualTo(Isolation.DEFAULT);
        assertThat(tx.propagation()).as("一个请求一个事务").isEqualTo(Propagation.REQUIRED);
        assertThat(update.getReturnType()).isEqualTo(Todo.class);
        assertThat(patchMapping().getReturnType()).as("PATCH 返回 TodoResponse（200 完整 todo）")
                .isEqualTo(com.todoapp.todo.dto.TodoResponse.class);
    }

    // —— helpers ——

    private void expectError(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        mvc.perform(request)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.length()").value(2));
    }

    /** 目标行快照 {@code title|completed|updatedAt}（updatedAt 用 wire 格式，便于与响应串直接比较）。 */
    private String row(UUID id) {
        return jdbc.sql("SELECT title, completed, updated_at FROM todo WHERE id = :id").param("id", id)
                .query((rs, n) -> rs.getString("title") + "|" + rs.getBoolean("completed") + "|"
                        + wire(rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    private String title(UUID id) {
        return jdbc.sql("SELECT title FROM todo WHERE id = :id").param("id", id).query(String.class).single();
    }

    private int codePoints(UUID id) {
        return jdbc.sql("SELECT char_length(title) FROM todo WHERE id = :id").param("id", id)
                .query(Integer.class).single();
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM todo").query(Long.class).single();
    }

    /** 与契约 Timestamp 相同的格式（固定 3 位毫秒、UTC、Z），只用于比对，不涉及被测逻辑。 */
    private static String wire(Instant instant) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(instant);
    }

    /** 混合大小写：把第一个 a-f 字母改成大写；（极少见的）全数字串则把末位换成 {@code A}。 */
    private static String mixedCase(String lower) {
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (ch >= 'a' && ch <= 'f') {
                return lower.substring(0, i) + Character.toUpperCase(ch) + lower.substring(i + 1);
            }
        }
        return lower.substring(0, lower.length() - 1) + "A";
    }

    private static MockHttpServletRequestBuilder patchJson(String rawId, String body) {
        return patch("/api/todos/{id}", rawId).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(UTF_8));
    }

    /** TodoController 上唯一的 {@code @PatchMapping("/{id}")} 方法。 */
    private static Method patchMapping() {
        List<Method> patches = Arrays.stream(TodoController.class.getDeclaredMethods())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, PatchMapping.class))
                .toList();
        assertThat(patches).as("TodoController 恰有一个 PATCH 映射").hasSize(1);
        PatchMapping mapping = AnnotatedElementUtils.findMergedAnnotation(patches.get(0), PatchMapping.class);
        assertThat(mapping.path().length > 0 ? mapping.path() : mapping.value()).containsExactly("/{id}");
        return patches.get(0);
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(PKG + name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(PKG + name + " 尚未实现（Story 3.1）", e);
        }
    }

    private static boolean classExists(String fqcn) {
        try {
            Class.forName(fqcn, false, TodoUpdateApiTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Method publicMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(type.getSimpleName() + "#" + name + " 尚未实现（Story 3.1 T3）", e);
        }
    }

    /**
     * {@code {"title":"…"}}：可打印 ASCII 之外的每个 UTF-16 单元（含孤立代理项）、{@code "} 与 {@code \} 都写成 {@code \\uXXXX}。
     * 只用于构造请求体，不涉及被测逻辑。
     */
    private static String titleJson(String title) {
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
