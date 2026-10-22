package com.todoapp.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守住 project-context「staging 测试的唯一入口」（后端部分）。本类本地运行，不带 needs-staging。
 *
 * <ul>
 *   <li>staging 测试只放在 {@code com.todoapp.staging}，都继承 {@code StagingTestBase}，不用 {@code @SpringBootTest}；</li>
 *   <li>staging 包以外不得读取 STAGING_BASE_URL，不得引用 {@code StagingTestBase}，也不得自己打 needs-staging 标签。</li>
 * </ul>
 */
@DisplayName("staging 测试入口边界")
class StagingBoundaryTest {

    private static final Path TEST_ROOT = moduleRoot().resolve("src/test/java/com/todoapp");
    private static final Path STAGING_DIR = TEST_ROOT.resolve("staging");
    private static final Path BASE = STAGING_DIR.resolve("StagingTestBase.java");
    private static final Path SELF = TEST_ROOT.resolve("support/StagingBoundaryTest.java");

    // 拆开写，避免本文件自己命中检查
    private static final String ENV_KEY = "STAGING" + "_BASE_URL";

    @Test
    @DisplayName("staging 包里的测试类都继承 StagingTestBase，且不在 JVM 内起服务")
    void stagingTestsGoThroughTheBase() throws IOException {
        List<Path> files = javaFiles(STAGING_DIR).stream().filter(p -> !p.equals(BASE)).toList();
        assertThat(files).as("至少有 StagingSmokeTest 这个示例").isNotEmpty();
        for (Path file : files) {
            String src = read(file);
            assertThat(src).as("%s 必须 extends StagingTestBase", file.getFileName())
                    .containsPattern("\\bextends\\s+StagingTestBase\\b");
            assertThat(src).as("%s 打的是已部署服务，不得用 @SpringBootTest / @IntegrationTest", file.getFileName())
                    .doesNotContain("@SpringBootTest").doesNotContain("@IntegrationTest");
        }
    }

    @Test
    @DisplayName("只有 StagingTestBase 读取 STAGING_BASE_URL")
    void onlyTheBaseReadsTheStagingAddress() throws IOException {
        List<Path> offenders = javaFiles(TEST_ROOT).stream()
                .filter(p -> !p.equals(BASE) && !p.equals(SELF))
                .filter(p -> read(p).contains(ENV_KEY))
                .toList();
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("staging 包以外不得引用 StagingTestBase，也不得自己打 needs-staging 标签")
    void nothingOutsideStagingUsesTheBaseOrTheTag() throws IOException {
        List<Path> offenders = javaFiles(TEST_ROOT).stream()
                .filter(p -> !p.startsWith(STAGING_DIR) && !p.equals(SELF))
                .filter(p -> {
                    String src = read(p);
                    return src.contains("StagingTestBase")
                            || src.contains("@Tag(TestTags.NEEDS_STAGING)")
                            || src.contains("@Tag(\"" + TestTags.NEEDS_STAGING + "\")");
                })
                .toList();
        assertThat(offenders).isEmpty();
    }

    private static Path moduleRoot() {
        // surefire 设置 basedir；IDE 里运行时退回工作目录
        String basedir = System.getProperty("basedir");
        Path root = Path.of(basedir != null ? basedir : System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(root.resolve("src/test/java/com/todoapp"))) {
            throw new IllegalStateException("找不到 todoapp-api 模块根（src/test/java/com/todoapp），当前：" + root);
        }
        return root;
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
