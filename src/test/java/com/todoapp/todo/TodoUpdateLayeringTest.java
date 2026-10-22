package com.todoapp.todo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.todoapp.todo.dto.TodoResponse;

import tools.jackson.databind.node.ObjectNode;

/**
 * Story 3.1 T6.2（BE-S2，AD-2、AD-6、AD-8、AD-11）：PATCH 路径的纯反射结构断言，不起 Spring、不引入 ArchUnit
 * （风格同 {@code TodoCreateLayeringTest}）。
 *
 * <p>反射测不到、留给 code review 核对的：UPDATE 为单条常量 SQL（不按字段动态拼接）、无先读后写、
 * PATCH 不取容量锁、事务内无远程调用、controller PATCH 方法体只调 {@code service.update}。
 */
@DisplayName("Story 3.1 PATCH 路径的事务边界与结构")
class TodoUpdateLayeringTest {

    @Test
    @DisplayName("[P2] BE-S2 TodoService.update(String, ObjectNode) 是 public、@Transactional 默认 REQUIRED / READ COMMITTED、非只读，返回 Todo")
    void serviceUpdateIsSingleTransactionalEntry() throws Exception {
        Method update = TodoService.class.getMethod("update", String.class, ObjectNode.class);
        assertThat(Modifier.isPublic(update.getModifiers())).isTrue();
        Transactional tx = AnnotatedElementUtils.findMergedAnnotation(update, Transactional.class);
        assertThat(tx).as("TodoService.update 带 @Transactional").isNotNull();
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(update.getReturnType()).isEqualTo(Todo.class);
    }

    @Test
    @DisplayName("[P2] BE-S2 TodoController 恰有一个 @PatchMapping(\"/{id}\")，参数 (@PathVariable String, @RequestBody ObjectNode)，返回 TodoResponse、默认 200；controller 方法参数无 UUID")
    void controllerPatchMapping() {
        List<Method> patches = Arrays.stream(TodoController.class.getDeclaredMethods())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, PatchMapping.class))
                .toList();
        assertThat(patches).hasSize(1);
        Method patch = patches.get(0);
        PatchMapping mapping = AnnotatedElementUtils.findMergedAnnotation(patch, PatchMapping.class);
        assertThat(mapping.path().length > 0 ? mapping.path() : mapping.value()).containsExactly("/{id}");
        assertThat(patch.getParameterTypes()).containsExactly(String.class, ObjectNode.class);
        assertThat(patch.getParameters()[0].isAnnotationPresent(PathVariable.class)).isTrue();
        assertThat(patch.getParameters()[1].isAnnotationPresent(RequestBody.class)).isTrue();
        assertThat(patch.getReturnType()).isEqualTo(TodoResponse.class);
        assertThat(AnnotatedElementUtils.hasAnnotation(patch, ResponseStatus.class)).as("默认 200").isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(patch, Transactional.class)).isFalse();
        assertThat(Arrays.stream(TodoController.class.getDeclaredMethods())
                .flatMap(m -> Arrays.stream(m.getParameterTypes())))
                .as("禁 @PathVariable UUID").doesNotContain(UUID.class);
    }

    @Test
    @DisplayName("[P2] BE-S2 TodoIds 是静态工具（final、私有构造器、非 Spring 组件、方法全 static、parse(String) → UUID）；dto 包无 PATCH 请求 record")
    void todoIdsIsStaticUtilityAndNoPatchDto() throws Exception {
        assertThat(Modifier.isFinal(TodoIds.class.getModifiers())).isTrue();
        assertThat(AnnotatedElementUtils.hasAnnotation(TodoIds.class, Component.class)).isFalse();
        for (Constructor<?> c : TodoIds.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPrivate(c.getModifiers())).isTrue();
        }
        assertThat(Arrays.stream(TodoIds.class.getDeclaredMethods()).filter(m -> !m.isSynthetic()))
                .allMatch(m -> Modifier.isStatic(m.getModifiers()));
        assertThat(TodoIds.class.getDeclaredMethod("parse", String.class).getReturnType()).isEqualTo(UUID.class);
        assertThat(Arrays.stream(TodoIds.class.getDeclaredFields()).<Class<?>>map(f -> f.getType()))
                .as("不放 long 常量（TodoCreateLayeringTest 的容量锁键唯一性）").doesNotContain(long.class);

        for (String name : List.of("UpdateTodoRequest", "PatchTodoRequest")) {
            assertThat(classExists("com.todoapp.todo.dto." + name)).as("不新建 PATCH DTO：%s", name).isFalse();
        }
    }

    @Test
    @DisplayName("[P2] BE-S2 TodoRepository.update(UUID, String, Boolean, Instant) 返回 Optional<Todo>、无 @Transactional，签名不含 dto 类型")
    void repositoryUpdateSignature() throws Exception {
        Method update = TodoRepository.class.getMethod("update", UUID.class, String.class, Boolean.class, Instant.class);
        assertThat(update.getReturnType()).isEqualTo(Optional.class);
        assertThat(((ParameterizedType) update.getGenericReturnType()).getActualTypeArguments())
                .containsExactly(Todo.class);
        assertThat(AnnotatedElementUtils.hasAnnotation(update, Transactional.class)).isFalse();
    }

    private static boolean classExists(String fqcn) {
        try {
            Class.forName(fqcn, false, TodoUpdateLayeringTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
