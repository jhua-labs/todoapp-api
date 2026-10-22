package com.todoapp.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import com.todoapp.support.IntegrationTest;

/**
 * Story 1.1 AC-3（BE-E1、BE-E2，AD-8）：领域异常、内部错误与 Jackson 严格模式都走唯一的
 * {@code @RestControllerAdvice}，输出 {@code {"error":{"code","message"}}}，code 只有 4 个，message 为中文，
 * 500 不暴露堆栈。
 */
@IntegrationTest
@Import(ErrorProbeController.class)
@DisplayName("Story 1.1 统一错误体")
class ApiErrorMappingTest {

    private static final String PROBE = "/api/__probe__";
    private static final String HAN = ".*\\p{IsHan}.*";
    private static final String REQUEST_BODY_MARK = "LOGMARK-500-标题-0e7d9";
    /** 框架异常的文案由实现定（固定中文、不含输入值），这里只要求是中文。 */
    private static final String ANY_CHINESE = HAN;

    @Autowired
    private MockMvc mvc;

    static Stream<Arguments> errorCases() {
        return Stream.of(
                // 领域异常：message 原样取 service 传入的中文
                Arguments.of("ValidationException → 400", get(PROBE + "/errors/validation"), 400, "VALIDATION_ERROR", exactly("标题不能为空")),
                Arguments.of("NotFoundException → 404", get(PROBE + "/errors/not-found"), 404, "NOT_FOUND", exactly("待办不存在")),
                Arguments.of("LimitExceededException → 409", get(PROBE + "/errors/limit"), 409, "LIMIT_EXCEEDED", exactly("待办已满，请先清理")),
                // BE-E2：其他异常 → 500，固定文案，不暴露堆栈与异常消息
                Arguments.of("未预期异常 → 500", get(PROBE + "/errors/unexpected"), 500, "INTERNAL_ERROR", exactly("服务器内部错误")),
                // Jackson 严格模式：数字/布尔不得强转成 String，字符串不得强转成 Boolean/Integer
                Arguments.of("title 为整数 → 400", echo("{\"title\":123}"), 400, "VALIDATION_ERROR", ANY_CHINESE),
                Arguments.of("title 为浮点 → 400", echo("{\"title\":1.5}"), 400, "VALIDATION_ERROR", ANY_CHINESE),
                Arguments.of("title 为布尔 → 400", echo("{\"title\":true}"), 400, "VALIDATION_ERROR", ANY_CHINESE),
                Arguments.of("completed 为字符串 \"true\" → 400", echo("{\"completed\":\"true\"}"), 400, "VALIDATION_ERROR", ANY_CHINESE),
                Arguments.of("Integer 字段为字符串 \"5\" → 400", echo("{\"n\":\"5\"}"), 400, "VALIDATION_ERROR", ANY_CHINESE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorCases")
    @DisplayName("[P0] AC-3 BE-E1/BE-E2 异常与严格模式经唯一 advice 输出契约错误体")
    void mapsToContractErrorBody(String scenario, RequestBuilder request, int expectedStatus,
                                 String expectedCode, String expectedMessagePattern) throws Exception {
        MvcResult result = mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.error.code").value(expectedCode))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.error.length()").value(2))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String message = com.jayway.jsonpath.JsonPath.read(body, "$.error.message");

        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(message).as("message 为中文").matches(HAN);
        assertThat(message).matches(expectedMessagePattern);
        assertThat(body)
                .as("响应体不暴露内部细节")
                .doesNotContain(ErrorProbeController.SECRET)
                .doesNotContain("Exception")
                .doesNotContain("\tat ")
                .doesNotContain("tools.jackson");
    }

    @Test
    @DisplayName("[P1] AC-3 未知字段被忽略（未自建 JsonMapper 覆盖 Boot 默认）")
    void ignoresUnknownFields() throws Exception {
        mvc.perform(echo("{\"title\":\"a\",\"x\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("a"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("[P1] AC-3 BE-E2 500 时服务器日志留堆栈，响应体不带")
    void logsStackTraceButHidesItFromResponse(CapturedOutput output) throws Exception {
        String body = mvc.perform(get(PROBE + "/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(output.getAll()).contains(ErrorProbeController.SECRET).contains("at com.todoapp.");
        assertThat(body).doesNotContain(ErrorProbeController.SECRET);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("[P1] AC-5 BE-E1 带请求体的 500 不把标题写入日志")
    void doesNotLogRequestBodyWhenRequestEndsIn500(CapturedOutput output) throws Exception {
        String json = "{\"title\":\"" + REQUEST_BODY_MARK + "\"}";

        mvc.perform(post(PROBE + "/errors/unexpected-with-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        assertThat(output.getAll())
                .contains(ErrorProbeController.SECRET)
                .doesNotContain(REQUEST_BODY_MARK);
    }

    /** 领域异常：message 必须原样等于 service 传入的文案。 */
    private static String exactly(String message) {
        return java.util.regex.Pattern.quote(message);
    }

    private static RequestBuilder echo(String json) {
        return post(PROBE + "/echo").contentType(MediaType.APPLICATION_JSON).content(json);
    }
}
