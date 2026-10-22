package com.todoapp.todo.dto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.todoapp.todo.Todo;

/**
 * 契约 {@code Todo}（§11.2）：字段固定 {@code id, title, completed, createdAt, updatedAt}。
 *
 * <p>时间字段是 {@code String}（AD-9）：固定 {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}（UTC、3 位毫秒）。
 * 不用 {@link Instant#toString()}——毫秒为 0 时它不输出 {@code .000}，有微秒时输出 6 位。
 */
public record TodoResponse(String id, String title, boolean completed, String createdAt, String updatedAt) {

    /** {@code 'Z'} 是字面量，所以必须 {@code withZone(UTC)}。 */
    private static final DateTimeFormatter WIRE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public static TodoResponse from(Todo todo) {
        return new TodoResponse(
                todo.id().toString(),
                todo.title(),
                todo.completed(),
                WIRE.format(todo.createdAt()),
                WIRE.format(todo.updatedAt()));
    }
}
