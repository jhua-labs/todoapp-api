package com.todoapp.support;

import javax.sql.DataSource;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 集成测试共用的 bean：冻结时钟与清表器。只通过 {@link IntegrationTest} 引入。
 *
 * <p>{@code @Primary}：生产代码（BE-0 的 {@code config}）提供 {@code Clock} bean 之后，
 * 注入点拿到的仍是这个可控时钟；生产侧还没有时也能单独工作。
 *
 * <p>{@code @Profile("!local")}：{@code spring-boot:test-run} 会把 test-classes 放进 classpath 并被组件扫描到，
 * 不加它冻结时钟就会混进本地开发运行（Story 1.1 T5，{@code LocalProfileIsolationTest} 守住）。
 * 测试不激活任何 profile，不受影响。
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("!local")
public class TestInfrastructureConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    DatabaseCleaner databaseCleaner(DataSource dataSource) {
        return new DatabaseCleaner(dataSource);
    }
}
