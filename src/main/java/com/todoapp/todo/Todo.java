package com.todoapp.todo;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code todo} 表的一行（AD-2：repository 返回的领域 record，不含 HTTP / JSON 细节）。
 *
 * <p>时间为毫秒精度的 UTC instant（列类型 {@code timestamptz(3)}，AD-9）；对外格式化只在 {@code todo.dto}。
 */
public record Todo(UUID id, String title, boolean completed, Instant createdAt, Instant updatedAt) {
}
