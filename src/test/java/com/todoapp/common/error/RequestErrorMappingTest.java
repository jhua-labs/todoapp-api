package com.todoapp.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.todoapp.support.IntegrationTest;

/**
 * Story 1.1 AC-5（BE-E1，AD-8 + Consistency Conventions「日志」）：
 * {@code /api} 下任何方法的未知路由 → 404；畸形 JSON / 缺请求体 / 非 JSON Content-Type / 不支持的方法 → 400；
 * 均走统一错误体，且请求体（标题）不进日志。
 *
 * <p>与 {@link ApiErrorMappingTest} 引入同一个探针，共用同一个缓存的 Spring 上下文。
 */
@IntegrationTest
@Import(ErrorProbeController.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Story 1.1 路由与请求体错误")
class RequestErrorMappingTest {

    private static final String PROBE = "/api/__probe__";
    private static final String HAN = ".*\\p{IsHan}.*";

    /** 每行请求体里的唯一标记（模拟标题）。日志里出现它即说明记录了请求体。 */
    private static final String MARK = "LOGMARK-标题-91c2e";

    @Autowired
    private MockMvc mvc;

    static Stream<Arguments> requestErrorCases() {
        return Stream.of(
                // 未知路由：任何方法都是 404（POST/DELETE 不得因静态资源处理器变成「方法不支持」）
                Arguments.of("GET 未知路由 → 404", get("/api/nope"), 404, "NOT_FOUND"),
                Arguments.of("POST 未知路由 → 404", post("/api/nope").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + MARK + "\"}"), 404, "NOT_FOUND"),
                Arguments.of("PUT 未知路由 → 404", put("/api/nope").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + MARK + "\"}"), 404, "NOT_FOUND"),
                Arguments.of("PATCH 未知路由 → 404", patch("/api/nope/x").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + MARK + "\"}"), 404, "NOT_FOUND"),
                Arguments.of("DELETE 未知路由 → 404", delete("/api/nope/" + "0b9a7c1e-1d2f-4a3b-8c4d-5e6f7a8b9c0d"), 404, "NOT_FOUND"),
                // 请求体问题 → 400
                Arguments.of("畸形 JSON → 400", post(PROBE + "/echo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + MARK), 400, "VALIDATION_ERROR"),
                Arguments.of("缺请求体 → 400", post(PROBE + "/echo").contentType(MediaType.APPLICATION_JSON), 400, "VALIDATION_ERROR"),
                Arguments.of("非 JSON Content-Type → 400", post(PROBE + "/echo").contentType(MediaType.TEXT_PLAIN)
                        .content(MARK), 400, "VALIDATION_ERROR"),
                // 不支持的方法 → 400（契约 ValidationError 描述明确包含「不支持的方法」，框架默认的 405 不得逃出契约）
                Arguments.of("不支持的方法 → 400", delete(PROBE + "/get-only"), 400, "VALIDATION_ERROR"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requestErrorCases")
    @DisplayName("[P0] AC-5 BE-E1 未知路由 404、请求体错误 400，统一错误体且不记录请求体")
    void mapsRoutingAndBodyErrors(String scenario, RequestBuilder request, int expectedStatus,
                                  String expectedCode, CapturedOutput output) throws Exception {
        mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.error.code").value(expectedCode))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error.length()").value(2))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.matchesPattern(HAN)));

        assertThat(output.getAll())
                .as("日志不得记录请求体（标题）")
                .doesNotContain(MARK);
    }
}
