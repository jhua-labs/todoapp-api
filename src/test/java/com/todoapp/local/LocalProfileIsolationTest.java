package com.todoapp.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.StandardEnvironment;

/**
 * Story 1.1 AC-2（INF-B2 补充 + AD-14）：测试基座与本地开发入口互相隔离。
 *
 * <p>{@code spring-boot:test-run} 会把 {@code target/test-classes} 放进 classpath，{@code @SpringBootApplication}
 * 从 {@code com.todoapp} 扫描时，测试侧的组件（冻结时钟 {@code TestInfrastructureConfig}、错误探针 controller）
 * 若没有 {@code @Profile("!local")} 就会混进本地运行。这里模拟 local profile 的组件扫描，
 * 断言来自 {@code src/test} 的候选只有 {@code LocalDbConfig}。
 *
 * <p>纯单元测试：不启动 Spring 上下文，也不启动本地开发库（绝不连 54329）。
 */
@DisplayName("Story 1.1 INF-B2 测试基座与 local 入口隔离")
class LocalProfileIsolationTest {

    private static final String BASE_PACKAGE = "com.todoapp";
    private static final String LOCAL_DB_CONFIG = "com.todoapp.local.LocalDbConfig";
    private static final String LOCAL_DEV_APPLICATION = "com.todoapp.local.LocalDevApplication";
    private static final int LOCAL_DB_PORT = 54329;

    @Test
    @DisplayName("[P0] AC-2 local 扫描下 src/test 只贡献 LocalDbConfig；它带 @TestConfiguration+@Profile(local)、端口 54329；src/test 只有 LocalDevApplication 带 main")
    void testInfrastructureDoesNotLeakIntoLocalRun() throws Exception {
        Set<String> testSourceCandidates = localProfileCandidates().stream()
                .filter(LocalProfileIsolationTest::isFromTestClasses)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(testSourceCandidates)
                .as("local profile 下被组件扫描到的 src/test 类（应只有 LocalDbConfig；其余要加 @Profile(\"!local\")）")
                .containsExactly(LOCAL_DB_CONFIG);

        Class<?> localDbConfig = Class.forName(LOCAL_DB_CONFIG, false, classLoader());
        assertThat(AnnotatedElementUtils.hasAnnotation(localDbConfig, TestConfiguration.class))
                .as("LocalDbConfig 必须标 @TestConfiguration（AD-14）")
                .isTrue();
        Profile profile = AnnotatedElementUtils.findMergedAnnotation(localDbConfig, Profile.class);
        assertThat(profile).as("LocalDbConfig 必须标 @Profile(\"local\")").isNotNull();
        assertThat(profile.value()).containsExactly("local");
        assertThat(staticIntConstants(localDbConfig))
                .as("LocalDbConfig 的固定端口常量（AD-14）")
                .contains(LOCAL_DB_PORT);

        assertThat(testClassesDeclaringMain())
                .as("src/test 下只允许 LocalDevApplication 带 main（AD-14）")
                .containsExactly(LOCAL_DEV_APPLICATION);
    }

    @Test
    @DisplayName("[P1] INF-B2 只有 public static void main(String[]) 才算 Java 入口")
    void recognizesOnlyJavaEntryPointSignature() {
        Method packagePrivateVoid = mainMethod(Modifier.STATIC, void.class);
        Method publicInt = mainMethod(Modifier.PUBLIC | Modifier.STATIC, int.class);
        Method publicVoid = mainMethod(Modifier.PUBLIC | Modifier.STATIC, void.class);

        assertThat(isMain(packagePrivateVoid)).isFalse();
        assertThat(isMain(publicInt)).isFalse();
        assertThat(isMain(publicVoid)).isTrue();
    }

    @Test
    @DisplayName("[P1] INF-B2 扫描到无法加载的 class 时必须失败而非静默假绿")
    void failsWhenScannedClassCannotBeLoaded() {
        assertThatThrownBy(() -> declaresMain("com.todoapp.local.DoesNotExist"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法检查 main 方法");
    }

    private static Method mainMethod(int modifiers, Class<?> returnType) {
        Method method = mock(Method.class);
        when(method.getName()).thenReturn("main");
        when(method.getModifiers()).thenReturn(modifiers);
        doReturn(returnType).when(method).getReturnType();
        when(method.getParameterTypes()).thenReturn(new Class<?>[] {String[].class});
        return method;
    }

    private static Set<String> localProfileCandidates() {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("local");
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true, env);
        return scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .collect(Collectors.toSet());
    }

    private static boolean isFromTestClasses(String className) {
        try {
            Class<?> type = Class.forName(className, false, classLoader());
            return testClassesRoot().equals(codeSourceRoot(type));
        } catch (ClassNotFoundException | URISyntaxException e) {
            throw new IllegalStateException("无法定位 " + className, e);
        }
    }

    private static List<Integer> staticIntConstants(Class<?> type) throws IllegalAccessException {
        List<Integer> values = new java.util.ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (Modifier.isStatic(mod) && Modifier.isFinal(mod) && field.getType() == int.class) {
                field.setAccessible(true);
                values.add(field.getInt(null));
            }
        }
        return values;
    }

    private static Set<String> testClassesDeclaringMain() throws IOException, URISyntaxException {
        Path root = testClassesRoot();
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(p -> p.toString().endsWith(".class"))
                    .map(p -> root.relativize(p).toString().replace('/', '.').replace('\\', '.'))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .filter(LocalProfileIsolationTest::declaresMain)
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static boolean declaresMain(String className) {
        try {
            Class<?> type = Class.forName(className, false, classLoader());
            return Arrays.stream(type.getDeclaredMethods()).anyMatch(LocalProfileIsolationTest::isMain);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("无法检查 main 方法：" + className, e);
        }
    }

    private static boolean isMain(Method m) {
        int modifiers = m.getModifiers();
        return m.getName().equals("main")
                && Modifier.isPublic(modifiers)
                && Modifier.isStatic(modifiers)
                && m.getReturnType() == void.class
                && Arrays.equals(m.getParameterTypes(), new Class<?>[] {String[].class});
    }

    private static Path testClassesRoot() throws URISyntaxException {
        return codeSourceRoot(LocalProfileIsolationTest.class);
    }

    private static Path codeSourceRoot(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
    }

    private static ClassLoader classLoader() {
        return LocalProfileIsolationTest.class.getClassLoader();
    }
}
