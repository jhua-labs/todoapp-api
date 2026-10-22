package com.todoapp.staging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * Story 4.2（OPS-1）staging 运行时证据：经 SSM 端口转发（本地 18080）→ web 的 nginx → api（AD-15）。
 *
 * <p>只有 staging 能证明（交互点表「前端 &lt;- nginx」「部署拓扑」两行）：nginx 的 SPA 回退、{@code /api} 不剥前缀地
 * 转发到 api 的统一错误处理、{@code index.html} 不缓存。标签、地址、HTTP 客户端都来自 {@link StagingTestBase}；
 * 默认被 pom 的 {@code excludedGroups=needs-staging} 排除，本地只检查能编译，由 5.9b 在 staging 上运行。
 *
 * <p>上游 502/503/504 → 500 契约体的运行时证明需要停掉 api，会扰动 staging，不写成自动化（README「部署后核验」的人工步骤）。
 * OPS-4（{@code ss -ltnp} / {@code docker compose ps} / SSM 三条件）是实例与 IAM 的基础设施事实，HTTP 无法证明，同样是人工核验。
 */
@DisplayName("Story 4.2 OPS-5 / AC-5 staging 部署冒烟与 nginx 边界")
class StagingDeploymentTest extends StagingTestBase {

    @Test
    @DisplayName("[P0] AC-1 OPS-5 冒烟：GET /api/todos 经 nginx 返回 200、application/json、{\"items\":[…]}（README 冒烟 curl 的自动化版）")
    void smokeListThroughNginxReturns200() throws Exception {
        HttpResponse<String> response = get("/api/todos");

        // 判定断言：compose 一键部署后整条链路（nginx → api → db）可用
        assertThat(response.statusCode()).as("GET /api/todos 经 nginx → 200，实际体：%s", response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        assertThat(json(response).path("items").isArray())
                .as("响应体必须是 {\"items\": [...]}，实际：%s", response.body())
                .isTrue();
    }

    @Test
    @DisplayName("[P1] AC-5 经 nginx：/api 不剥前缀到达 api 统一错误处理（未知路由 404 NOT_FOUND 契约体），非 /api 路径 try_files 回退到 index.html 且 no-cache")
    void nginxForwardsApiUnstrippedAndFallsBackToIndexHtml() throws Exception {
        HttpResponse<String> unknownApi = get("/api/__no_such_route__");

        // 判定断言：/api 前缀原样转发到 api:8080（剥了前缀会落到 api 的别的路径，nginx 自己的 404 是 HTML）
        assertThat(unknownApi.statusCode()).as("GET /api/__no_such_route__ 经 nginx 到达 api，返回 404").isEqualTo(404);
        assertThat(unknownApi.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).as("404 来自 api 的契约错误体，不是 nginx 的 HTML 页").startsWith("application/json"));
        JsonNode error = json(unknownApi).path("error");
        assertThat(error.path("code").asString()).as("契约错误体 {\"error\":{\"code\":\"NOT_FOUND\",…}}，实际：%s", unknownApi.body())
                .isEqualTo("NOT_FOUND");

        HttpResponse<String> index = send(request("/").setHeader("Accept", "text/html").GET().build());
        assertThat(index.statusCode()).as("GET / → 200").isEqualTo(200);
        assertThat(index.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("text/html"));

        HttpResponse<String> spaPath = send(request("/some/spa/path").setHeader("Accept", "text/html").GET().build());
        assertThat(spaPath.statusCode()).as("非 /api 路径 try_files $uri /index.html → 200").isEqualTo(200);
        assertThat(spaPath.body()).as("SPA 回退返回的就是 index.html").isEqualTo(index.body());
        assertThat(spaPath.headers().firstValue("Cache-Control")).as("index.html（含 SPA 回退命中）不缓存")
                .hasValueSatisfying(cc -> assertThat(cc).contains("no-cache"));
    }
}
