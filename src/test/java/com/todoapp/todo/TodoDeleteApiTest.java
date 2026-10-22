package com.todoapp.todo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.OffsetDateTime;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.todoapp.support.IntegrationTest;
import com.todoapp.support.TodoSeeder;

/**
 * Story 3.2 接口层：{@code DELETE /api/todos/{id}}（FR-7、FR-8、§11.3 DELETE、§11.4、SM-5、AD-6、AD-8、AD-11、
 * 契约 {@code deleteTodo}）。
 *
 * <ul>
 *   <li>AC-1（BE-D1）：删除存在的待办 → 204 无响应体、行从表中移除、其余行不变；再删同一 id → 404；</li>
 *   <li>AC-2（BE-D2）：格式合法但不存在、格式非法（含已存在 id 的大写形式）的 id → 404 {@code NOT_FOUND}，不是 400；</li>
 *   <li>AC-3（BE-P10）：清单满 500 时经 API 删一条后，POST 可再次成功；</li>
 *   <li>AC-4（BE-E1、BE-S2）：DELETE 不检查请求体、不记录请求体；404 走统一错误体；delete 是单一事务入口。</li>
 * </ul>
 *
 * <p>ATDD 红阶段（5.2）：每条 AC 一个叶子。只经 HTTP、直接 SQL 与反射访问，
 * 不在编译期引用本 story 新增的生产符号（{@code TodoService.delete}、{@code TodoRepository.delete}），
 * 整套在实现前就能编译。实现前 {@code /api/todos/{单段}} 上只有 PATCH 映射，DELETE 得 400「方法不支持」，
 * 所以每个叶子的第一个状态断言（204 / 404 / 201）都会失败。
 *
 * <p>红阶段的跳过注解已在 5.3 完成 T1–T3 后全部去掉（断言未改，见 atdd-checklist-3-2-*.md）。
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Story 3.2 DELETE /api/todos/{id}")
class TodoDeleteApiTest {

    /** 请求体里的唯一标记：出现在日志里即说明记录了请求体（Consistency Conventions「日志」）。 */
    private static final String MARK = "LOGMARK-删除请求体-7d41a";

    /** 格式合法（小写规范 UUID）但库里一定不存在的 id（每个测试前清表，id 由库随机生成）。 */
    private static final String UNKNOWN_ID = "0b9a7c1e-1d2f-4a3b-8c4d-5e6f7a8b9c0d";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    // —— AC-1 ——

    @Test
    @DisplayName("[P0] AC-1 BE-D1 DELETE 存在的待办 → 204 无响应体、该行从表中移除、其余行与顺序不变；再删同一 id → 404 NOT_FOUND")
    void deletesExistingTodoWith204AndNoBody() throws Exception {
        List<UUID> ids = TodoSeeder.seed(jdbc, 3);
        UUID target = ids.get(1);
        List<String> othersBefore = snapshotExcept(target);

        mvc.perform(delete("/api/todos/{id}", target.toString()))
                // 本 AC 最先可能失败的断言：204（实现前为 400「方法不支持」）
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(notJsonContentType());

        assertThat(exists(target)).as("被删的行已从表中移除").isFalse();
        assertThat(count()).as("总数 3 → 2").isEqualTo(2);
        assertThat(snapshotExcept(target)).as("其余两行 id/title/completed/created_at/updated_at 逐字不变")
                .isEqualTo(othersBefore);

        // GET 只剩另外两条，且相对顺序不变（createdAt 倒序：seed 的第 2 条在前、第 0 条在后）
        mvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(ids.get(2).toString()))
                .andExpect(jsonPath("$.items[1].id").value(ids.get(0).toString()));

        // AD-11：影响 0 行 → 404（第二次删除不是 204）
        expectNotFound(delete("/api/todos/{id}", target.toString()));
        assertThat(count()).isEqualTo(2);
        assertThat(snapshotExcept(target)).isEqualTo(othersBefore);
    }

    // —— AC-2 ——

    @Test
    @DisplayName("[P0] AC-2 BE-D2 DELETE 格式合法但不存在的 id、或格式非法的 id（含已存在 id 的大写形式）→ 都是 404 NOT_FOUND（不是 400），数据不变")
    void unknownOrMalformedIdIsNotFound() throws Exception {
        UUID existing = TodoSeeder.insert(jdbc, TodoSeeder.Row.created("买牛奶", false, TodoSeeder.DEFAULT_BASE));
        List<String> before = snapshotExcept(null);

        mvc.perform(delete("/api/todos/{id}", UNKNOWN_ID))
                // 本 AC 最先可能失败的断言：404（实现前为 400「方法不支持」）
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        // 5.3 补：同一请求再按统一错误体完整断一遍（T4.6；DELETE 幂等地 404，数据不变）
        expectNotFound(delete("/api/todos/{id}", UNKNOWN_ID));
        assertThat(snapshotExcept(null)).isEqualTo(before);

        String lower = existing.toString();
        List<String> malformed = List.of(
                "abc",
                "1-1-1-1-1",
                lower.toUpperCase(Locale.ROOT),          // UUID.fromString 能解析：必须证明没删掉真实存在的那条
                mixedCase(lower),
                lower.replace("-", ""),                  // 32 位无连字符
                lower.substring(0, lower.length() - 1),  // 35 位
                lower + "0",                             // 37 位
                "g" + lower.substring(1));               // 含非十六进制字符
        for (String raw : malformed) {
            expectNotFound(delete("/api/todos/{id}", raw));
            assertThat(snapshotExcept(null)).as("非法 id [%s] 不改数据", raw).isEqualTo(before);
        }
        assertThat(exists(existing)).as("大写 / 混合大小写形式没有删掉真实存在的那条").isTrue();
        assertThat(count()).isEqualTo(1);
    }

    // —— AC-3 ——

    @Test
    @DisplayName("[P2] AC-3 BE-P10 清单恰好 500 条时经 API DELETE 一条后再 POST → 201，总数 500 → 499 → 500")
    void deleteFreesCapacityForNextCreate() throws Exception {
        List<UUID> ids = TodoSeeder.seed(jdbc, 500);
        // 前置守卫（Story 2.2 已实现的行为）：满 500 时 POST 被挡下
        mvc.perform(postTitle("满了还想加"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LIMIT_EXCEEDED"));
        assertThat(count()).isEqualTo(500);

        UUID victim = ids.get(250);
        // 状态迁移：经 API 删除一条（响应在判定断言前保持不透明，不在这里断言）
        mvc.perform(delete("/api/todos/{id}", victim.toString()));

        mvc.perform(postTitle("回落后可再新增"))
                // 本 AC 最先可能失败的断言：201（实现前 DELETE 没删掉任何行，POST 仍是 409）
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("回落后可再新增"));

        assertThat(exists(victim)).as("被删的那条确实经 API 移除").isFalse();
        assertThat(count()).as("删一条加一条，回到 500").isEqualTo(500);
        // 回到上限后再次被挡下（容量判定没有被删除绕过）
        expectError(postTitle("又满了"), 409, "LIMIT_EXCEEDED");
    }

    // —— AC-4 ——

    @Test
    @DisplayName("[P0] AC-4 BE-E1/BE-S2 DELETE 不检查请求体（text/plain、畸形 JSON 都照常 204）且不记录请求体；404 走统一错误体；delete 是单一事务入口")
    void ignoresRequestBodyAndUsesUnifiedErrorBody(CapturedOutput output) throws Exception {
        UUID plain = TodoSeeder.insert(jdbc, TodoSeeder.Row.created("纯文本体", false, TodoSeeder.DEFAULT_BASE));
        UUID broken = TodoSeeder.insert(jdbc,
                TodoSeeder.Row.created("畸形 JSON 体", false, TodoSeeder.DEFAULT_BASE.plusMillis(1)));

        mvc.perform(delete("/api/todos/{id}", plain.toString())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(("任意文本 " + MARK).getBytes(UTF_8)))
                // 本 AC 最先可能失败的断言：204（AD-8「GET/DELETE 不检查请求体」；实现前为 400）
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(exists(plain)).as("带 text/plain 请求体的 DELETE 确实删掉了那一行").isFalse();

        mvc.perform(delete("/api/todos/{id}", broken.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"title\":\"" + MARK).getBytes(UTF_8)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(exists(broken)).as("带畸形 JSON 请求体的 DELETE 确实删掉了那一行").isFalse();
        assertThat(count()).isZero();

        assertThat(output.getAll()).as("日志不记录请求体").doesNotContain(MARK);

        // 统一错误体（AD-8）：顶层只有 error，error 只有 code/message，code 精确为 NOT_FOUND
        mvc.perform(delete("/api/todos/{id}", UNKNOWN_ID))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/json")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error.length()").value(2))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").isString())
                .andExpect(jsonPath("$.error.message").value(not(emptyString())));

        // 结构（AD-2、AD-6、AD-8）：运行期反射，不在编译期引用尚不存在的方法
        Method service = publicMethod(TodoService.class, "delete", String.class);
        Transactional tx = AnnotatedElementUtils.findMergedAnnotation(service, Transactional.class);
        assertThat(tx).as("TodoService.delete 带 @Transactional").isNotNull();
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(service.getReturnType()).isEqualTo(void.class);

        List<Method> deletes = Arrays.stream(TodoController.class.getDeclaredMethods())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, DeleteMapping.class))
                .toList();
        assertThat(deletes).as("TodoController 恰有一个 DELETE 映射").hasSize(1);
        Method mapping = deletes.get(0);
        DeleteMapping dm = AnnotatedElementUtils.findMergedAnnotation(mapping, DeleteMapping.class);
        assertThat(dm.path().length > 0 ? dm.path() : dm.value()).containsExactly("/{id}");
        assertThat(dm.consumes()).as("不写 consumes（不检查请求体）").isEmpty();
        assertThat(dm.produces()).as("不写 produces（204 无响应体）").isEmpty();
        assertThat(mapping.getParameterTypes()).containsExactly(String.class);
        assertThat(mapping.getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
        assertThat(Arrays.stream(mapping.getParameters()).anyMatch(p -> p.isAnnotationPresent(RequestBody.class)))
                .as("不收 @RequestBody").isFalse();
        ResponseStatus rs = AnnotatedElementUtils.findMergedAnnotation(mapping, ResponseStatus.class);
        assertThat(rs).as("@ResponseStatus(NO_CONTENT)").isNotNull();
        assertThat(rs.code()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mapping.getReturnType()).isEqualTo(void.class);
        assertThat(AnnotatedElementUtils.hasAnnotation(mapping, Transactional.class)).isFalse();

        Method repo = publicMethod(TodoRepository.class, "delete", UUID.class);
        assertThat(repo.getReturnType()).as("repository 返回影响行数，0 行 → 404 由 service 判定").isEqualTo(int.class);
        assertThat(AnnotatedElementUtils.hasAnnotation(repo, Transactional.class)).isFalse();
        assertThat(Modifier.isPublic(repo.getModifiers())).isTrue();

        assertThat(classExists("com.todoapp.todo.dto.DeleteTodoRequest")).as("不新建 DELETE DTO").isFalse();
    }

    // —— helpers ——

    private void expectNotFound(MockHttpServletRequestBuilder request) throws Exception {
        expectError(request, 404, "NOT_FOUND");
    }

    private void expectError(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        mvc.perform(request)
                .andExpect(status().is(status))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/json")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error.length()").value(2))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString())
                .andExpect(jsonPath("$.error.message").value(not(emptyString())));
    }

    /** 204 响应不带 JSON 体：Content-Type 要么不存在，要么不是 application/json。 */
    private static ResultMatcher notJsonContentType() {
        return result -> {
            String type = result.getResponse().getContentType();
            assertThat(type == null || !type.startsWith("application/json"))
                    .as("204 不带 JSON 错误体，实际 Content-Type=%s", type).isTrue();
        };
    }

    /** 除 {@code excluded} 外全部行的快照，按 created_at 排序（{@code excluded == null} 即全表）。 */
    private List<String> snapshotExcept(UUID excluded) {
        return jdbc.sql("SELECT id, title, completed, created_at, updated_at FROM todo ORDER BY created_at")
                .query((rs, n) -> rs.getObject("id", UUID.class) + "|" + rs.getString("title") + "|"
                        + rs.getBoolean("completed") + "|" + rs.getObject("created_at", OffsetDateTime.class).toInstant()
                        + "|" + rs.getObject("updated_at", OffsetDateTime.class).toInstant())
                .list().stream()
                .filter(line -> excluded == null || !line.startsWith(excluded + "|"))
                .toList();
    }

    private boolean exists(UUID id) {
        return jdbc.sql("SELECT count(*) FROM todo WHERE id = :id").param("id", id).query(Long.class).single() > 0;
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM todo").query(Long.class).single();
    }

    private static MockHttpServletRequestBuilder postTitle(String title) {
        return post("/api/todos").contentType(MediaType.APPLICATION_JSON)
                .content(("{\"title\":\"" + title + "\"}").getBytes(UTF_8));
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

    private static boolean classExists(String fqcn) {
        try {
            Class.forName(fqcn, false, TodoDeleteApiTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Method publicMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(type.getSimpleName() + "#" + name + " 尚未实现（Story 3.2）", e);
        }
    }
}
