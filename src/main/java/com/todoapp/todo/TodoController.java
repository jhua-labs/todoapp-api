package com.todoapp.todo;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.todoapp.todo.dto.CreateTodoRequest;
import com.todoapp.todo.dto.TodoListResponse;
import com.todoapp.todo.dto.TodoResponse;

import tools.jackson.databind.node.ObjectNode;

/**
 * {@code /api/todos} 的 HTTP 映射（AD-2）：只做映射与 DTO 转换，只调 {@link TodoService}。
 *
 * <p>目前有 GET（Story 2.1）、POST（Story 2.2）、PATCH（Story 3.1）与 DELETE（Story 3.2）。
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService service;

    public TodoController(TodoService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public TodoListResponse list() {
        return new TodoListResponse(service.list().stream().map(TodoResponse::from).toList());
    }

    /** 新增（FR-1、§11.3）：201 + 完整 todo。类型错 / 畸形 JSON / 非 JSON 由框架异常经统一错误体映射为 400。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TodoResponse create(@RequestBody CreateTodoRequest request) {
        return TodoResponse.from(service.create(request.title()));
    }

    /**
     * 修改（FR-5、FR-6、§11.3）：200 + 改完的完整 todo。
     * 路径 id 以 {@code String} 接收（禁 {@code @PathVariable UUID}：非规范 id 要 404 而不是 400，AD-8）；
     * 请求体以 {@link ObjectNode} 接收，由 service 区分缺省 / 显式 null / 类型不符（禁 record / Optional 作 PATCH DTO）。
     */
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TodoResponse update(@PathVariable("id") String id, @RequestBody ObjectNode body) {
        return TodoResponse.from(service.update(id, body));
    }

    /**
     * 删除（FR-7、§11.3）：204 无响应体。路径 id 以 {@code String} 接收（非规范 id → 404，AD-8）。
     * 不收 {@code @RequestBody}、不写 {@code consumes}（AD-8「GET/DELETE 不检查请求体」）；
     * 不写 {@code produces}（成功无响应体，错误体由 {@code ApiErrorHandler} 显式设 JSON）。
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }
}
