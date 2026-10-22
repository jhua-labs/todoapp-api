package com.todoapp.todo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.todoapp.support.MutableClock;

/**
 * Story 2.1 AC-5（BE-S2，AD-2、AD-3、AD-6、AD-9）：纯反射的结构断言，不起 Spring、不引入 ArchUnit。
 *
 * <p>红阶段生产类还不存在，一律按类名反射加载，保证整套测试现在就能编译。
 * {@code TodoService.now()} 按 AD-9 保持私有，用反射调用（story 偏差 1），不要为测试放宽可见性。
 *
 * <p>反射测不到的部分（repository 的 SQL 为常量且只用命名参数、无业务分支；controller 只做映射）
 * 留给 code review 对照 AD-2 / AD-3 核对。
 */
@DisplayName("Story 2.1 todo 包分层与取时入口")
class TodoLayeringTest {

    private static final String PKG = "com.todoapp.todo.";

    @Test
    @DisplayName("[P2] AC-5 BE-S2 TodoService.now() 截断到毫秒；controller→service→repository 单向依赖、读方法 readOnly 事务、repository 返回 Todo record")
    void layeringAndSingleTimeSource() throws Exception {
        Class<?> service = load("TodoService");
        Class<?> repository = load("TodoRepository");
        Class<?> controller = load("TodoController");
        Class<?> todo = load("Todo");

        // AD-9：唯一取时入口 now() = clock.instant().truncatedTo(MILLIS)——本 AC 最先可能失败的断言
        // MutableClock 默认 .123456 → 截断为 .123（不是四舍五入成 .124）
        Object serviceInstance = newWithClock(service, new MutableClock());
        Method now = service.getDeclaredMethod("now");
        now.setAccessible(true);
        assertThat(now.invoke(serviceInstance)).isEqualTo(Instant.parse("2026-01-15T02:30:00.123Z"));
        assertThat(Modifier.isPrivate(now.getModifiers())).as("now() 是私有方法（AD-9）").isTrue();
        assertThat(now.getReturnType()).isEqualTo(Instant.class);

        // AD-2：controller 只依赖 service
        assertThat(onlyConstructorParams(controller)).containsExactly(service);
        assertThat(fieldTypes(controller)).doesNotContain(repository, JdbcClient.class, Clock.class);

        // AD-2：service 只依赖 repository 与 config bean（Clock）
        assertThat(onlyConstructorParams(service)).containsExactlyInAnyOrder(repository, Clock.class);

        // AD-6：读方法 list() 是 public 且 @Transactional(readOnly = true)
        Method list = service.getMethod("list");
        Transactional tx = AnnotatedElementUtils.findMergedAnnotation(list, Transactional.class);
        assertThat(tx).as("TodoService.list 带 @Transactional").isNotNull();
        assertThat(tx.readOnly()).as("读方法 readOnly = true").isTrue();

        // AD-6：@Transactional 只在 service；repository、controller 的类与方法上都没有
        assertThat(transactionalElements(repository)).isEmpty();
        assertThat(transactionalElements(controller)).isEmpty();

        // AD-3：repository 只依赖 JdbcClient
        assertThat(onlyConstructorParams(repository)).containsExactly(JdbcClient.class);

        // AD-2：Todo 是 record；repository 的 GET 查询返回 List<Todo>
        assertThat(todo.isRecord()).as("todo.Todo 是 record").isTrue();
        Type findAll = repository.getMethod("findAllNewestFirst").getGenericReturnType();
        assertThat(findAll).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) findAll).getRawType()).isEqualTo(List.class);
        assertThat(((ParameterizedType) findAll).getActualTypeArguments()).containsExactly(todo);

        // AD-2：todo.dto 下的类型不进入 repository 的任何方法签名
        assertThat(signatureTypeNames(repository)).noneMatch(name -> name.contains(PKG + "dto."));
    }

    private static Class<?> load(String simpleName) {
        try {
            return Class.forName(PKG + simpleName);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(PKG + simpleName + " 尚未实现（Story 2.1 T3）", e);
        }
    }

    private static Class<?>[] onlyConstructorParams(Class<?> type) {
        Constructor<?>[] ctors = type.getDeclaredConstructors();
        assertThat(ctors).as("%s 只有一个构造器（构造注入）", type.getSimpleName()).hasSize(1);
        return ctors[0].getParameterTypes();
    }

    /** 用唯一构造器创建实例：Clock 参数传入给定时钟，其余（repository）传 null——now() 不碰它。 */
    private static Object newWithClock(Class<?> type, Clock clock) throws Exception {
        Constructor<?> ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = Arrays.stream(ctor.getParameterTypes())
                .map(p -> Clock.class.isAssignableFrom(p) ? clock : null)
                .toArray();
        return ctor.newInstance(args);
    }

    private static List<Class<?>> fieldTypes(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(Field::getType).<Class<?>>map(c -> c).toList();
    }

    private static List<String> transactionalElements(Class<?> type) {
        List<String> found = new ArrayList<>();
        if (AnnotatedElementUtils.hasAnnotation(type, Transactional.class)) {
            found.add(type.getSimpleName());
        }
        for (Method m : type.getDeclaredMethods()) {
            if (AnnotatedElementUtils.hasAnnotation(m, Transactional.class)) {
                found.add(type.getSimpleName() + "#" + m.getName());
            }
        }
        return found;
    }

    private static List<String> signatureTypeNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            names.add(m.getGenericReturnType().getTypeName());
            for (Type p : m.getGenericParameterTypes()) {
                names.add(p.getTypeName());
            }
        }
        return names;
    }
}
