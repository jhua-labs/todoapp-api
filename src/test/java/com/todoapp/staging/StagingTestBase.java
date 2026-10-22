package com.todoapp.staging;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import com.todoapp.support.TestTags;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * staging 测试的唯一入口（project-context「staging 测试的唯一入口」）。
 *
 * <ul>
 *   <li>类级别 {@code @Tag("needs-staging")}（{@code @Tag} 可继承）：pom 默认排除，本地 / PR 不跑。</li>
 *   <li>不用 {@code @SpringBootTest}：不在 JVM 里起本服务，而是经 HTTP 打已部署的 staging。</li>
 *   <li>地址只从 {@code STAGING_BASE_URL} 读（环境变量，或同名 {@code -D} 系统属性），默认
 *       {@code http://127.0.0.1:18080}，即 SSM 端口转发的本地端口（AD-15）。</li>
 *   <li>其他测试不得读取 {@code STAGING_BASE_URL}，也不得继承本类；{@code StagingBoundaryTest} 守住这两条。</li>
 * </ul>
 *
 * <p>建好 SSM 隧道后运行：{@code todoapp-api/mvnw -f todoapp-api/pom.xml test -Dgroups=needs-staging}
 */
@Tag(TestTags.NEEDS_STAGING)
public abstract class StagingTestBase {

    public static final String BASE_URL_KEY = "STAGING_BASE_URL";
    /** AD-15：SSM 转发的本地端口固定为 18080。 */
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:18080";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static HttpClient http;
    private static URI baseUrl;

    @BeforeAll
    static void openStagingClient() {
        baseUrl = resolveBaseUrl();
        http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @AfterAll
    static void closeStagingClient() {
        if (http != null) {
            http.close();
            http = null;
        }
    }

    /** 已部署服务的根地址（不带末尾斜杠）。 */
    protected static URI baseUrl() {
        return baseUrl;
    }

    protected static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(request(path).GET().build());
    }

    protected static HttpResponse<String> postJson(String path, String json) throws IOException, InterruptedException {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    protected static HttpResponse<String> patchJson(String path, String json) throws IOException, InterruptedException {
        return send(request(path).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build());
    }

    protected static HttpResponse<String> delete(String path) throws IOException, InterruptedException {
        return send(request(path).DELETE().build());
    }

    /** 自定义请求的起点：已带地址、超时和 {@code Accept: application/json}。 */
    protected static HttpRequest.Builder request(String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path 必须以 / 开头：" + path);
        }
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json");
    }

    protected static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        if (http == null) {
            throw new IllegalStateException("HTTP 客户端只在测试类生命周期内可用（@BeforeAll 之后）");
        }
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    protected static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    static URI resolveBaseUrl() {
        String fromProperty = System.getProperty(BASE_URL_KEY);
        String fromEnv = System.getenv(BASE_URL_KEY);
        String raw = notBlank(fromProperty) ? fromProperty : notBlank(fromEnv) ? fromEnv : DEFAULT_BASE_URL;
        String trimmed = raw.strip().replaceAll("/+$", "");
        URI uri = URI.create(trimmed);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalStateException(BASE_URL_KEY + " 必须是 http(s)://host[:port] 形式（不带路径），实际：" + raw);
        }
        return uri;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
