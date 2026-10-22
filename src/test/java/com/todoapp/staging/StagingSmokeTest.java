package com.todoapp.staging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * staging 测试示例，同时是 OPS-5 的冒烟部分：经 SSM 端口转发（本地 18080，AD-15）访问已部署的 staging。
 * OPS-5 的另一半（SSM 三条件）属于基础设施核验，由 OPS story 补齐。
 *
 * <p>标签、地址、HTTP 客户端都来自 {@link StagingTestBase}。默认被 pom 的 {@code excludedGroups=needs-staging} 排除。
 */
@DisplayName("needs-staging 示例 / OPS-5 冒烟部分")
class StagingSmokeTest extends StagingTestBase {

    @Test
    @DisplayName("GET /api/todos 经 nginx 返回 200，响应体是 {\"items\": [...]}")
    void listEndpointIsReachableThroughNginx() throws Exception {
        HttpResponse<String> response = get("/api/todos");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        JsonNode body = json(response);
        assertThat(body.path("items").isArray())
                .as("响应体必须是 {\"items\": [...]}，实际：%s", response.body())
                .isTrue();
    }
}
