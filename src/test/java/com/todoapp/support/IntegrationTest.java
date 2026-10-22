package com.todoapp.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;

/**
 * 后端集成测试的唯一入口（AD-16 / INF-B2）。用组合注解代替继承基类，测试类只需要：
 *
 * <pre>{@code
 * @IntegrationTest
 * class TodoListApiTest {
 *     @Autowired MockMvc mvc;
 *     @Autowired MutableClock clock;
 * }
 * }</pre>
 *
 * 提供：
 * <ul>
 *   <li>{@code @SpringBootTest} + MockMvc（MOCK web 环境）；</li>
 *   <li>embedded PostgreSQL 18.6，随机端口 + 临时目录，Flyway 在上下文启动时照常迁移；</li>
 *   <li>{@code server.address=127.0.0.1}：起真实端口时也只监听回环（AD-14）；</li>
 *   <li>不激活任何 profile（尤其不是 {@code local}）；</li>
 *   <li>每个测试前清空业务表并把 {@link MutableClock} 复位（先于 {@code @Sql} 播种和测试事务）。</li>
 * </ul>
 *
 * 需要真实 HTTP 端口的测试（如 BE-2 并发）在测试类上另写
 * {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}，其余配置照样生效（见 {@code RandomPortFrameworkTest}）。
 *
 * <p>整套测试共用一个库并在每个测试前清表，只能顺序执行（{@code junit-platform.properties} 已关闭并行）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestEnvironmentInitializer.class)
@Import(TestInfrastructureConfig.class)
@TestExecutionListeners(
        listeners = ResetTestStateListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface IntegrationTest {
}
