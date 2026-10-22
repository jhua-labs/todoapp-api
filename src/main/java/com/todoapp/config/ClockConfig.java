package com.todoapp.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 唯一的时间来源（AD-9）：UTC 系统时钟。bean 本身不截断，截断到毫秒只发生在 {@code TodoService.now()}。
 *
 * <p>集成测试里由 {@code @Primary MutableClock} 顶替（bean 名 {@code testClock}，这里不能同名）。
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
