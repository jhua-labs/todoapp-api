package com.todoapp.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import com.todoapp.support.IntegrationTest;
import com.todoapp.support.TodoSeeder;
import com.todoapp.support.TodoSeeder.Row;

/**
 * Story 2.1 接口层：{@code GET /api/todos}（§11.3、AD-9、契约 {@code TodoList} / {@code Todo} / {@code Timestamp}）。
 *
 * <ul>
 *   <li>AC-3（BE-G1、BE-G3）：按 {@code created_at} 倒序、{@code completed} 不参与排序；时间固定 3 位毫秒 UTC；id 小写 UUID；</li>
 *   <li>AC-4（BE-G2）：空表 → {@code {"items":[]}}；</li>
 *   <li>AC-6（BE-E1 回归）：{@code /api/todos} 下的未知子路径 → 404 {@code NOT_FOUND}。</li>
 * </ul>
 *
 * <p>播种用 {@link TodoSeeder} 直接 SQL（测试前基座已清表）。surefire 以 {@code Asia/Shanghai} 运行，
 * 期望串全部是 UTC：误用系统时区会差 8 小时。
 */
@IntegrationTest
@DisplayName("Story 2.1 GET /api/todos")
class TodoListApiTest {

    private static final String UUID_LOWER = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    private static final String WIRE_TIME = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$";
    private static final String HAN = ".*\\p{IsHan}.*";

    /** 整秒时刻：证明毫秒段为 0 时仍输出 {@code .000}（{@code Instant.toString()} 会丢掉它）。 */
    private static final Instant T0 = Instant.parse("2026-01-15T02:30:00.000Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("[P0] AC-3 BE-G1/BE-G3 非空清单按 createdAt 倒序（completed 不参与排序），时间固定 3 位毫秒 UTC、id 小写 UUID")
    void listsNewestFirstWithWireFormat() throws Exception {
        // Given 三条待办：最旧的已完成；插入顺序 ≠ 期望顺序（证明靠 ORDER BY，不靠插入顺序）
        Row oldest = Row.created("买牛奶", true, T0);
        Row newest = new Row("提交周报", false, T0.plus(Duration.ofHours(2)),
                T0.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(5)).plusSeconds(30).plusMillis(250));
        Row middle = Row.created("给妈妈打电话", false, T0.plusMillis(1));
        List<UUID> ids = TodoSeeder.seed(jdbc, List.of(oldest, newest, middle));
        String idOldest = ids.get(0).toString();
        String idNewest = ids.get(1).toString();
        String idMiddle = ids.get(2).toString();

        // When / Then
        mvc.perform(get("/api/todos"))
                // 本 AC 最先可能失败的断言：200
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/json")))
                // 顶层只有 items；严格倒序：T+2h、T+1ms、T
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[*].id").value(contains(idNewest, idMiddle, idOldest)))
                .andExpect(jsonPath("$.items[*].createdAt").value(contains(
                        "2026-01-15T04:30:00.000Z", "2026-01-15T02:30:00.001Z", "2026-01-15T02:30:00.000Z")))
                // updatedAt 与 createdAt 各自映射（最新一条 updatedAt 晚于 createdAt）
                .andExpect(jsonPath("$.items[*].updatedAt").value(contains(
                        "2026-01-15T04:35:30.250Z", "2026-01-15T02:30:00.001Z", "2026-01-15T02:30:00.000Z")))
                .andExpect(jsonPath("$.items[*].title").value(contains("提交周报", "给妈妈打电话", "买牛奶")))
                .andExpect(jsonPath("$.items[*].completed").value(contains(false, false, true)))
                // 契约格式：每条恰好 5 个字段；时间与 id 的格式
                .andExpect(jsonPath("$.items[0].length()").value(5))
                .andExpect(jsonPath("$.items[1].length()").value(5))
                .andExpect(jsonPath("$.items[2].length()").value(5))
                .andExpect(jsonPath("$.items[*].createdAt").value(everyItem(matchesPattern(WIRE_TIME))))
                .andExpect(jsonPath("$.items[*].updatedAt").value(everyItem(matchesPattern(WIRE_TIME))))
                .andExpect(jsonPath("$.items[*].id").value(everyItem(matchesPattern(UUID_LOWER))));
    }

    @Test
    @DisplayName("[P0] AC-3 BE-G1 500 条规模：一次返回全部 500 条且整体严格按 createdAt 倒序（不做耗时断言）")
    void listsFiveHundredNewestFirst() throws Exception {
        // Given 500 条（INF-B3）：createdAt = base + i ms，插入顺序为从旧到新
        List<UUID> ids = TodoSeeder.seed(jdbc, 500);
        List<String> expectedIds = ids.reversed().stream().map(UUID::toString).toList();

        mvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(500))
                // 整体严格倒序：id 序列恰好是插入序列的逆序（不分页、不截断）
                .andExpect(jsonPath("$.items[*].id").value(contains(expectedIds.toArray())))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-01-14T02:30:00.622Z"))
                .andExpect(jsonPath("$.items[499].createdAt").value("2026-01-14T02:30:00.123Z"));
    }

    @Test
    @DisplayName("[P0] AC-3 BE-G3 时区回归：JVM 默认时区为 Asia/Shanghai 时输出仍是 UTC（跨日时刻不平移 8 小时）")
    void wireTimeIsUtcRegardlessOfJvmZone() throws Exception {
        // 前提：surefire 以 Asia/Shanghai 运行，否则本用例证明不了什么
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Shanghai");

        // UTC 23:59:59.999 在上海已是次日 07:59:59.999：误用系统时区会连日期一起错
        Instant lateUtc = Instant.parse("2026-01-15T23:59:59.999Z");
        TodoSeeder.insert(jdbc, Row.created("跨日", false, lateUtc));

        mvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-01-15T23:59:59.999Z"))
                .andExpect(jsonPath("$.items[0].updatedAt").value("2026-01-15T23:59:59.999Z"));
    }

    @Test
    @DisplayName("[P1] AC-4 BE-G2 空表返回 200 与 {\"items\":[]}")
    void emptyListIsItemsArray() throws Exception {
        // Given 表为空（基座每个测试前清表）
        mvc.perform(get("/api/todos"))
                // 本 AC 最先可能失败的断言：200
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/json")))
                .andExpect(content().json("{\"items\":[]}", JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("[P0] AC-6 BE-E1 /api/todos 下未知子路径返回 404 NOT_FOUND 统一错误体")
    void unknownSubPathIsNotFound() throws Exception {
        mvc.perform(get("/api/todos/extra/path"))
                // 本 AC 最先可能失败的断言：404
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error.length()").value(2))
                .andExpect(jsonPath("$.error.message").value(matchesPattern(HAN)));
    }
}
