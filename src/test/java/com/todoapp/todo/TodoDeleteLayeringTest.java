package com.todoapp.todo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Story 3.2 T5（BE-S2，AD-2、AD-6、AD-8、AD-11）：DELETE 路径的纯反射结构断言，不起 Spring、不引入 ArchUnit
 * （风格同 {@code TodoUpdateLayeringTest}）。
 *
 * <p>反射测不到、留给 code review 核对的：DELETE 为单条常量 SQL、无先读后写、不取容量锁、
 * 事务内无远程调用、controller DELETE 方法体只调 {@code service.delete}。
 */
@DisplayName("Story 3.2 DELETE 路径的事务边界与结构")
class TodoDeleteLayeringTest {

    @Test
    @DisplayName("[P2] BE-S2 TodoService.delete(String) 是 public、@Transactional 默认 REQUIRED / READ COMMITTED、非只读，返回 void")
    void serviceDeleteIsSingleTransactionalEntry() throws Exception {
        Method delete = TodoService.class.getMethod("delete", String.class);
        assertThat(Modifier.isPublic(delete.getModifiers())).isTrue();
        Transactional tx = AnnotatedElementUtils.findMergedAnnotation(delete, Transactional.class);
        assertThat(tx).as("TodoService.delete 带 @Transactional").isNotNull();
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(delete.getReturnType()).isEqualTo(void.class);
    }

    @Test
    @DisplayName("[P2] BE-S2 TodoController 恰有一个 @DeleteMapping(\"/{id}\")，参数 (@PathVariable String)、无 @RequestBody，@ResponseStatus(NO_CONTENT)，返回 void，无 consumes / produces / @Transactional")
    void controllerDeleteMapping() {
        List<Method> deletes = Arrays.stream(TodoController.class.getDeclaredMethods())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, DeleteMapping.class))
                .toList();
        assertThat(deletes).hasSize(1);
        Method delete = deletes.get(0);
        DeleteMapping mapping = AnnotatedElementUtils.findMergedAnnotation(delete, DeleteMapping.class);
        assertThat(mapping.path().length > 0 ? mapping.path() : mapping.value()).containsExactly("/{id}");
        assertThat(mapping.consumes()).as("不写 consumes（AD-8 不检查请求体）").isEmpty();
        assertThat(mapping.produces()).as("不写 produces（204 无响应体）").isEmpty();
        assertThat(delete.getParameterTypes()).containsExactly(String.class);
        assertThat(delete.getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
        assertThat(Arrays.stream(delete.getParameters()).anyMatch(p -> p.isAnnotationPresent(RequestBody.class)))
                .as("不收 @RequestBody").isFalse();
        ResponseStatus status = AnnotatedElementUtils.findMergedAnnotation(delete, ResponseStatus.class);
        assertThat(status).isNotNull();
        assertThat(status.code()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete.getReturnType()).isEqualTo(void.class);
        assertThat(AnnotatedElementUtils.hasAnnotation(delete, Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("[P2] BE-S2 TodoRepository.delete(UUID) 是 public、返回 int（影响行数）、无 @Transactional")
    void repositoryDeleteSignature() throws Exception {
        Method delete = TodoRepository.class.getMethod("delete", UUID.class);
        assertThat(Modifier.isPublic(delete.getModifiers())).isTrue();
        assertThat(delete.getReturnType()).isEqualTo(int.class);
        assertThat(AnnotatedElementUtils.hasAnnotation(delete, Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("[P2] BE-S2 dto 包无 DELETE 请求类")
    void noDeleteDto() {
        for (String name : List.of("DeleteTodoRequest", "RemoveTodoRequest")) {
            assertThat(classExists("com.todoapp.todo.dto." + name)).as("不新建 DELETE DTO：%s", name).isFalse();
        }
    }

    private static boolean classExists(String fqcn) {
        try {
            Class.forName(fqcn, false, TodoDeleteLayeringTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
