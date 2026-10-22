package com.todoapp.todo.dto;

import java.util.List;

/** 契约 {@code TodoList}：列表一律包装为 {@code {"items":[…]}}（§11.3）。 */
public record TodoListResponse(List<TodoResponse> items) {
}
