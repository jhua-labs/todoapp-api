package com.todoapp.todo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Story 2.2 AC-6（BE-S2，AD-2、AD-6、AD-7、AD-10）：纯反射的结构断言，不起 Spring、不引入 ArchUnit。
 *
 * <p>ATDD 红阶段：{@code TitleRules}、{@code CreateTodoRequest} 尚不存在，一律按类名反射加载，整套现在就能编译。
 * 2.1 的 {@code TodoLayeringTest}（构造器、{@code now()} 私有、repository / controller 无 {@code @Transactional}）不改，
 * 跑全量确认不回归即可，这里不重复它的断言。
 *
 * <p>反射测不到的部分（controller 的 POST 方法体只调 {@code service.create}；只有 create 取容量锁；事务内无远程调用）
 * 留给 code review 对照 AD-2 / AD-6 / AD-10 核对。
 */
@DisplayName("Story 2.2 新增路径的事务边界与结构")
class TodoCreateLayeringTest {

    private static final String PKG = "com.todoapp.todo.";

    /** surefire 工作目录是 todoapp-api basedir；主代码编译输出里的 todo 包。 */
    private static final Path MAIN_TODO_CLASSES = Path.of("target", "classes", "com", "todoapp", "todo");

    @Test
    @DisplayName("[P2] AC-6 BE-S2 TodoService.create 是唯一事务入口：@Transactional 默认 REQUIRED / READ COMMITTED、非只读；POST 只映射到它；TitleRules 为静态工具；容量锁常量键只在 TodoRepository")
    void createIsSingleTransactionalEntry() throws Exception {
        Class<?> service = load("TodoService");
        Class<?> todo = load("Todo");
        Method create = publicMethod(service, "create", String.class);

        // AD-6——本 AC 最先可能失败的断言：create 带 @Transactional
        Transactional tx = AnnotatedElementUtils.findMergedAnnotation(create, Transactional.class);
        assertThat(tx).as("TodoService.create 带 @Transactional").isNotNull();
        assertThat(tx.readOnly()).as("写方法不是 readOnly").isFalse();
        assertThat(tx.isolation()).as("隔离级别用 PostgreSQL 默认 READ COMMITTED，不调高").isEqualTo(Isolation.DEFAULT);
        assertThat(tx.propagation()).as("一个请求一个事务").isEqualTo(Propagation.REQUIRED);
        assertThat(create.getReturnType()).as("service 返回领域 record，不返回 DTO").isEqualTo(todo);

        // AD-2：POST 映射在 controller，只有一个参数 CreateTodoRequest、返回 TodoResponse、201
        Class<?> controller = load("TodoController");
        Class<?> request = load("dto.CreateTodoRequest");
        Class<?> response = load("dto.TodoResponse");
        List<Method> posts = Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, PostMapping.class))
                .toList();
        assertThat(posts).as("TodoController 恰有一个 POST 映射").hasSize(1);
        Method post = posts.get(0);
        assertThat(post.getParameterTypes()).containsExactly(request);
        assertThat(post.getReturnType()).isEqualTo(response);
        ResponseStatus rs = AnnotatedElementUtils.findMergedAnnotation(post, ResponseStatus.class);
        assertThat(rs).as("POST 带 @ResponseStatus(CREATED)").isNotNull();
        assertThat(rs.code()).isEqualTo(HttpStatus.CREATED);
        assertThat(request.isRecord()).as("CreateTodoRequest 是 record").isTrue();
        assertThat(Arrays.stream(request.getRecordComponents()).map(c -> c.getName()))
                .as("请求 DTO 只有 title").containsExactly("title");

        // AD-7 / story「为什么 TitleRules 必须是静态工具」：final、私有构造器、非 Spring 组件、方法全为 static
        Class<?> titleRules = load("TitleRules");
        assertThat(Modifier.isFinal(titleRules.getModifiers())).as("TitleRules 是 final 类").isTrue();
        assertThat(AnnotatedElementUtils.hasAnnotation(titleRules, Component.class))
                .as("TitleRules 不是 Spring 组件").isFalse();
        for (Constructor<?> c : titleRules.getDeclaredConstructors()) {
            assertThat(Modifier.isPrivate(c.getModifiers())).as("TitleRules 只有私有构造器").isTrue();
        }
        assertThat(Arrays.stream(titleRules.getDeclaredMethods()).filter(m -> !m.isSynthetic()))
                .as("TitleRules 的方法全为 static")
                .allMatch(m -> Modifier.isStatic(m.getModifiers()));
        Method normalize = titleRules.getDeclaredMethod("normalize", String.class);
        assertThat(normalize.getReturnType()).isEqualTo(String.class);

        // AD-10：常量键只在 TodoRepository 定义（static final long，包级可见供同包测试占锁）
        Field key = TodoRepository.class.getDeclaredField("CAPACITY_LOCK_KEY");
        assertThat(key.getType()).isEqualTo(long.class);
        assertThat(Modifier.isStatic(key.getModifiers()) && Modifier.isFinal(key.getModifiers())).isTrue();
        key.setAccessible(true);
        long keyValue = key.getLong(null);
        assertThat(lockKeyLookalikes(keyValue))
                .as("todo 包其他主代码类里没有同名或同值的 long 常量").isEmpty();
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(PKG + name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(PKG + name + " 尚未实现（Story 2.2）", e);
        }
    }

    private static Method publicMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(type.getSimpleName() + "#" + name + " 尚未实现（Story 2.2 T3）", e);
        }
    }

    /** 扫 {@code target/classes} 下 todo 包（含 dto）的全部主代码类，找除 TodoRepository 外同名或同值的 static long 字段。 */
    private static List<String> lockKeyLookalikes(long keyValue) throws IOException, IllegalAccessException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_TODO_CLASSES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String rel = MAIN_TODO_CLASSES.relativize(p).toString();
                String className = PKG + rel.substring(0, rel.length() - ".class".length()).replace('/', '.').replace('\\', '.');
                if (className.equals(TodoRepository.class.getName())) {
                    continue;
                }
                Class<?> type;
                try {
                    type = Class.forName(className, false, TodoCreateLayeringTest.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    continue;
                }
                for (Field f : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || f.getType() != long.class) {
                        continue;
                    }
                    f.setAccessible(true);
                    if (f.getName().equals("CAPACITY_LOCK_KEY") || f.getLong(null) == keyValue) {
                        found.add(type.getSimpleName() + "#" + f.getName());
                    }
                }
            }
        }
        return found;
    }
}
