package com.todoapp.common.error;

import java.util.Map;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 1.1 错误映射的测试探针（还没有业务 controller，AD-8 的映射表只能靠它触发）。
 *
 * <ul>
 *   <li>{@code @TestComponent}：不会被其他测试上下文的组件扫描带进来，只由测试类 {@code @Import} 显式引入；</li>
 *   <li>{@code @Profile("!local")}：{@code spring-boot:test-run} 的本地运行里不会出现这个接口（Story 1.1 T5）。</li>
 * </ul>
 */
@TestComponent
@RestController
@Profile("!local")
@RequestMapping("/api/__probe__")
public class ErrorProbeController {

    /** 内部异常里的机密标记：响应体里绝不能出现（AD-8「500 不暴露堆栈」）。 */
    public static final String SECRET = "PROBE_SECRET_7f3a9c";

    @GetMapping("/errors/{kind}")
    public Map<String, String> raise(@PathVariable("kind") String kind) {
        throw switch (kind) {
            case "validation" -> new ValidationException("标题不能为空");
            case "not-found" -> new NotFoundException("待办不存在");
            case "limit" -> new LimitExceededException("待办已满，请先清理");
            case "unexpected" -> new IllegalStateException(SECRET + " 内部细节，不得出现在响应里");
            default -> new IllegalArgumentException("未知探针 kind: " + kind);
        };
    }

    /** 绑定请求体后抛内部异常：验证 500 日志不会记录请求体内容。 */
    @PostMapping(path = "/errors/unexpected-with-body", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProbeBody unexpectedWithBody(@RequestBody ProbeBody body) {
        throw new IllegalStateException(SECRET + " 请求处理失败");
    }

    /** 回显请求体：用来触发畸形 JSON、缺体、非 JSON Content-Type、Jackson 严格模式拒绝强转。 */
    @PostMapping(path = "/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProbeBody echo(@RequestBody ProbeBody body) {
        return body;
    }

    /** 只支持 GET：用别的方法打它，触发 HttpRequestMethodNotSupportedException。 */
    @GetMapping("/get-only")
    public Map<String, String> getOnly() {
        return Map.of("ok", "ok");
    }

    public record ProbeBody(String title, Boolean completed, Integer n) {
    }
}
