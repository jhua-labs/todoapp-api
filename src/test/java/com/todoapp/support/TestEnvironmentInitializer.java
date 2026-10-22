package com.todoapp.support;

import java.util.Map;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * 以最高优先级注入测试环境属性：
 * <ul>
 *   <li>{@code spring.datasource.*} 指向测试 embedded PG。DataSource（Hikari 连接池）和 Flyway 都走 Boot 的
 *       正常自动配置路径，跟 staging 一致；BE-2 并发测试拿到的就是真连接池。</li>
 *   <li>{@code server.address=127.0.0.1}：{@code RANDOM_PORT} 测试起的 Tomcat 只监听回环，
 *       无鉴权 API 不会在测试期间暴露到局域网（AD-14）。</li>
 * </ul>
 */
public class TestEnvironmentInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String PROPERTY_SOURCE_NAME = "todoappTestEnvironment";
    static final String LOOPBACK = "127.0.0.1";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Map<String, Object> props = Map.of(
                "spring.datasource.url", TestDatabase.jdbcUrl(),
                "spring.datasource.username", TestDatabase.USER,
                "spring.datasource.password", "",
                "server.address", LOOPBACK);
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
    }
}
