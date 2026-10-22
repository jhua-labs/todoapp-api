package com.todoapp.todo.dto;

/**
 * {@code POST /api/todos} 请求体（契约 {@code CreateTodoRequest}）。只有 {@code title}；
 * 客户端传来的 {@code id} / {@code completed} / 时间等未知字段一律忽略（AD-8，不信任客户端数据）。
 * 校验与裁剪在 service（{@code TitleRules}），这里不做。
 */
public record CreateTodoRequest(String title) {
}
