package com.todoapp.local;

import org.springframework.boot.SpringApplication;

import com.todoapp.TodoApplication;

/**
 * 本地开发入口（AD-14）。在工作区根执行：
 *
 * <pre>todoapp-api/mvnw -f todoapp-api/pom.xml spring-boot:test-run</pre>
 *
 * 激活 {@code local} profile，并引入 {@link LocalDbConfig}（embedded PG，端口 54329，数据目录持久保留）。
 * 后端监听 {@code 127.0.0.1:8080}。{@code src/test} 下只允许这一个带 {@code main} 的类。
 *
 * <p>故意不加任何 Spring 注解：它只是启动器，不能被组件扫描成配置类。
 */
public final class LocalDevApplication {

    private LocalDevApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.from(TodoApplication::main)
                .with(LocalDbConfig.class)
                .withAdditionalProfiles("local")
                .run(args);
    }
}
