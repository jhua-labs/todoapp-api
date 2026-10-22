package com.todoapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。测试框架只需要它作为 {@code @SpringBootTest} 的配置根；
 * 业务包（todo、common.error、config）由后续 story 补齐。
 */
@SpringBootApplication
public class TodoApplication {

    public static void main(String[] args) {
        SpringApplication.run(TodoApplication.class, args);
    }
}
