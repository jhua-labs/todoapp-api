package com.todoapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import com.todoapp.support.IntegrationTest;
import com.todoapp.support.MutableClock;

/**
 * Story 1.1 AC-4（AD-3、AD-9、AD-14）：生产侧提供唯一的 UTC 系统 {@code Clock} bean（不截断），
 * 测试注入点仍拿到冻结的 {@link MutableClock}；{@code application.yaml} 设
 * {@code server.address=127.0.0.1}、{@code spring.sql.init.mode=never}。
 *
 * <p>{@code server.address} 直接读 {@code application.yaml}：测试基座的 {@code TestEnvironmentInitializer}
 * 会以最高优先级注入 127.0.0.1，从 {@code Environment} 读会掩盖主配置漏写。
 */
@IntegrationTest
@DisplayName("Story 1.1 时间来源与基础配置")
class ClockAndServerConfigTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Clock injectedClock;

    @Autowired
    private MutableClock testClock;

    @Test
    @DisplayName("[P0] AC-4 生产 Clock bean 为 UTC 系统时钟且测试仍注入 MutableClock；主配置回环监听、SQL 初始化关闭")
    void productionClockAndBaseConfig() throws Exception {
        List<Clock> productionClocks = context.getBeansOfType(Clock.class).values().stream()
                .filter(c -> !(c instanceof MutableClock))
                .toList();

        assertThat(productionClocks).as("生产侧 Clock bean（config 包）").hasSize(1);
        // equals(systemUTC) 同时证明 bean 未包装截断（截断只在 Story 2.1 的 TodoService.now()，AD-9）
        assertThat(productionClocks.getFirst()).isEqualTo(Clock.systemUTC());
        assertThat(injectedClock).as("@Primary 冻结时钟仍优先").isSameAs(testClock);

        PropertySource<?> mainYaml = loadApplicationYaml();
        assertThat(mainYaml.getProperty("server.address")).hasToString("127.0.0.1");
        assertThat(mainYaml.getProperty("spring.sql.init.mode")).hasToString("never");
    }

    @Test
    @DisplayName("[P1] AC-4 staging 配置只监听容器网卡（AD-15）且不含数据源地址与密码；local 配置不含密码")
    void profileYamlsCarryNoCredentials() throws Exception {
        PropertySource<?> staging = loadYaml("application-staging.yaml");
        assertThat(staging.getProperty("server.address")).hasToString("0.0.0.0");
        assertThat(staging.getProperty("spring.datasource.url")).as("staging 数据源来自环境变量").isNull();
        assertThat(staging.getProperty("spring.datasource.password")).isNull();

        PropertySource<?> local = loadYaml("application-local.yaml");
        assertThat(local.getProperty("spring.datasource.password")).as("任何密码都不提交").isNull();
        assertThat(local.getProperty("todoapp.local-db.embedded")).hasToString("true");
    }

    private static PropertySource<?> loadApplicationYaml() throws Exception {
        return loadYaml("application.yaml");
    }

    private static PropertySource<?> loadYaml(String name) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(name, new ClassPathResource(name));
        assertThat(sources).as("%s 只有一个文档", name).hasSize(1);
        return sources.getFirst();
    }
}
