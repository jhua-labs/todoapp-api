package com.todoapp.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Story 4.2（OPS-1，STATIC）：staging 部署产物的静态断言（AD-15、NFR-安全边界、R-4）。
 *
 * <p>只读本 repo 的部署文件：{@code deploy/compose.yaml}（用 SnakeYAML 解析成 Map，不对原文 grep，注释不会误判）、
 * {@code Dockerfile}、{@code deploy/.env.example}、{@code deploy/README.md}、{@code .gitignore}，
 * 外加只读的同级 {@code ../todoapp-web/Dockerfile}（compose 的 {@code web.build.context} 本来就依赖同级目录布局）。
 * 不依赖 Docker、网络、端口，在 run_loop accept 命令（{@code -DexcludedGroups=needs-staging}）下是确定的。
 *
 * <p>纯 JUnit：不带 Spring 注解、没有 main 方法、不打任何标签；运行时暴露面、SSM 三条件、nginx 真实行为
 * 只有 staging 能证明，放在 {@code com.todoapp.staging} 包里（本类不引用那边的任何东西）。
 *
 * <p>红阶段：部署文件都还不存在。读取缺失文件时返回空内容（空 Map / 空串），
 * 让每个叶子都在自己的判定断言处失败，而不是在读文件时抛 {@code NoSuchFileException}。
 */
@DisplayName("Story 4.2 OPS-1 部署产物静态断言")
class DeployArtifactsTest {

    private static final Path MODULE_ROOT = moduleRoot();
    private static final Path COMPOSE = MODULE_ROOT.resolve("deploy/compose.yaml");
    private static final Path ENV_EXAMPLE = MODULE_ROOT.resolve("deploy/.env.example");
    private static final Path README = MODULE_ROOT.resolve("deploy/README.md");
    private static final Path API_DOCKERFILE = MODULE_ROOT.resolve("Dockerfile");
    private static final Path GITIGNORE = MODULE_ROOT.resolve(".gitignore");
    private static final Path WEB_DOCKERFILE = MODULE_ROOT.resolveSibling("todoapp-web").resolve("Dockerfile");

    /** 纯插值：{@code ${VAR}} 或必填形式 {@code ${VAR:?msg}} / {@code ${VAR?msg}}；不允许 {@code :-默认值}（默认值就是字面密码）。 */
    private static final Pattern PURE_INTERPOLATION =
            Pattern.compile("^\\$\\{[A-Za-z_][A-Za-z0-9_]*(?::?\\?[^}]*)?}$");

    /** .env.example 里密码类键允许的值：空，或明显的占位。 */
    private static final Pattern PASSWORD_PLACEHOLDER =
            Pattern.compile("^(?:|(?i:change[-_]?me)|<[^>]+>)$");

    // ------------------------------------------------------------------ AC-1

    @Test
    @DisplayName("[P1] AC-1 compose 恰好三服务 db/api/web：postgres:18.6 + pgdata + UTF8 + pg_isready、api 等 db 健康、web 依赖 api、restart/日志统一，api/web 多阶段构建基镜像正确")
    void composeDefinesThreeServicesWithPinnedImagesAndMultiStageBuilds() {
        Map<String, Object> services = services();

        // 判定断言：compose 一键构建部署的就是这三个服务（服务名被 nginx.conf 的 api:8080 写死）
        assertThat(services.keySet())
                .as("deploy/compose.yaml 的 services 必须恰好是 db、api、web 三个（AD-15）")
                .containsExactlyInAnyOrder("db", "api", "web");

        Map<String, Object> top = compose();
        assertThat(top).as("顶层不写已废弃的 version:（Compose Specification）").doesNotContainKey("version");

        // db
        Map<String, Object> db = map(services.get("db"));
        assertThat(db.get("image")).as("db.image（Stack 表钉死）").isEqualTo("postgres:18.6");
        assertThat(env(db)).as("db.environment 必须带 POSTGRES_INITDB_ARGS=--encoding=UTF8（码点校验依赖 UTF8，R-3）")
                .containsEntry("POSTGRES_INITDB_ARGS", "--encoding=UTF8");
        assertThat(healthcheckTest(db)).as("db.healthcheck.test 用 pg_isready（api 的 service_healthy 依赖它）")
                .contains("pg_isready");
        assertThat(volumeMounts(db))
                .as("db 用命名卷 pgdata，挂在 /var/lib/postgresql（postgres 18+ 镜像的 VOLUME，不是旧的 /data）")
                .contains("pgdata:/var/lib/postgresql");
        assertThat(map(top.get("volumes"))).as("顶层 volumes 声明 pgdata").containsKey("pgdata");

        // api
        Map<String, Object> api = map(services.get("api"));
        assertThat(buildContext(api)).as("api.build.context（相对 deploy/，即 todoapp-api/）").isEqualTo("..");
        assertThat(dependsOnCondition(api, "db"))
                .as("api.depends_on.db.condition").isEqualTo(Optional.of("service_healthy"));

        // web
        Map<String, Object> web = map(services.get("web"));
        assertThat(buildContext(web)).as("web.build.context（同级 todoapp-web）").isEqualTo("../../todoapp-web");
        assertThat(dependsOnNames(web)).as("web.depends_on 含 api").contains("api");

        // 三服务统一的 restart 与日志
        for (String name : List.of("db", "api", "web")) {
            Map<String, Object> svc = map(services.get(name));
            assertThat(svc.get("restart")).as("%s.restart", name).isEqualTo("unless-stopped");
            Map<String, Object> logging = map(svc.get("logging"));
            assertThat(logging.get("driver")).as("%s.logging.driver", name).isEqualTo("json-file");
            Map<String, Object> options = map(logging.get("options"));
            assertThat(String.valueOf(options.get("max-size"))).as("%s.logging.options.max-size", name).isEqualTo("10m");
            assertThat(String.valueOf(options.get("max-file"))).as("%s.logging.options.max-file", name).isEqualTo("3");
        }

        // api 镜像：maven 构建 → JRE 运行，恰好两个 FROM（多阶段）
        List<String> apiDocker = nonCommentLines(API_DOCKERFILE);
        List<String> apiFroms = fromLines(apiDocker);
        assertThat(apiFroms).as("todoapp-api/Dockerfile 恰好两个 FROM（多阶段），依次是 maven 构建与 JRE 运行")
                .hasSize(2);
        assertThat(apiFroms.get(0)).as("构建阶段基镜像").matches("(?i)FROM\\s+maven:3\\.9-eclipse-temurin-21(\\s+AS\\s+\\S+)?");
        assertThat(apiFroms.get(1)).as("运行阶段基镜像").matches("(?i)FROM\\s+eclipse-temurin:21-jre-noble(\\s+AS\\s+\\S+)?");
        int apiBuildFrom = apiDocker.indexOf(apiFroms.get(0));
        int apiRunFrom = apiDocker.indexOf(apiFroms.get(1));
        int mvnPackage = indexOfMatching(apiDocker, Pattern.compile("^RUN\\s+.*\\bmvn\\s+-B\\s+-DskipTests\\s+package\\b.*$"));
        assertThat(mvnPackage).as("构建阶段 RUN mvn -B -DskipTests package（用镜像里的 mvn，AD-15）")
                .isGreaterThan(apiBuildFrom).isLessThan(apiRunFrom);

        // web 镜像（FE-0 交付，只读）：node 构建 → nginx 托管
        assertThat(Files.isRegularFile(WEB_DOCKERFILE))
                .as("找不到同级 todoapp-web/Dockerfile（%s）：compose 的 web.build.context 依赖同级目录布局", WEB_DOCKERFILE)
                .isTrue();
        List<String> webDocker = nonCommentLines(WEB_DOCKERFILE);
        List<String> webFroms = fromLines(webDocker);
        assertThat(webFroms).as("todoapp-web/Dockerfile 恰好两个 FROM").hasSize(2);
        assertThat(webFroms.get(0)).matches("(?i)FROM\\s+node:24-bookworm-slim(\\s+AS\\s+\\S+)?");
        assertThat(webFroms.get(1)).matches("(?i)FROM\\s+nginx:1\\.30-alpine(\\s+AS\\s+\\S+)?");
        String webBuildStage = String.join("\n", webDocker.subList(webDocker.indexOf(webFroms.get(0)), webDocker.indexOf(webFroms.get(1))));
        assertThat(webBuildStage).as("web 构建阶段执行 npm ci 与 npm run build").contains("npm ci").contains("npm run build");
    }

    // ------------------------------------------------------------------ AC-2

    @Test
    @DisplayName("[P0] AC-2 OPS-1 只有 web 发布端口且恰好 127.0.0.1:8080:80，api/db 不发布，无 network_mode、无 0.0.0.0、无第四个组件")
    void onlyWebPublishesExactlyLoopback8080() {
        Map<String, Object> services = services();

        // 判定断言：不可协商硬底线——web 的端口发布恰好一条，且带回环主机 IP
        assertThat(publishedPorts(services.get("web")))
                .as("web.ports 必须恰好一项 127.0.0.1:8080:80（省略主机 IP 会绑 0.0.0.0，直接违反 NFR-安全边界）")
                .containsExactly("127.0.0.1:8080:80");

        assertThat(map(services.get("api"))).as("api 不得发布任何端口").doesNotContainKey("ports");
        assertThat(map(services.get("db"))).as("db 不得发布任何端口").doesNotContainKey("ports");
        assertThat(services.keySet())
                .as("不存在任何其他让服务可达的组件（反向代理 / 隧道 / VPN 容器一律禁止）：只有 db、api、web")
                .containsExactlyInAnyOrder("db", "api", "web");
        for (Map.Entry<String, Object> e : services.entrySet()) {
            assertThat(map(e.getValue())).as("%s 不得写 network_mode（host 会绕过端口发布；nginx 依赖 compose 默认网络的 127.0.0.11）", e.getKey())
                    .doesNotContainKey("network_mode");
        }
        List<String> scalars = new ArrayList<>();
        collectScalars(compose(), scalars);
        assertThat(scalars).as("compose 解析后的任何值都不得含 0.0.0.0")
                .noneMatch(s -> s.contains("0.0.0.0"));

        // 不在本 repo 文件里的那半条（实例层：公网 IP / ALB / EIP / 安全组）只能写进 README，由人在 staging 核验
        String readme = readOrEmpty(README);
        assertThat(readme).as("README 写明实例不分配公网 IP、不挂 ALB/EIP、安全组零入站")
                .contains("公网 IP").contains("ALB").contains("EIP").contains("安全组");
        assertThat(readme).as("README 写明禁止反向代理、NAT、对外 VPN/跳板、隧道")
                .contains("反向代理").contains("NAT").contains("VPN").contains("隧道");
    }

    // ------------------------------------------------------------------ AC-3

    @Test
    @DisplayName("[P0] AC-3 OPS-2 README 写明内网-only、仅限 staging 的 SSM 三条例外（IAM / 无公网入站 / 只转发 127.0.0.1:8080、本地 18080）、只放测试数据、部署流程")
    void readmeStatesIntranetOnlyAndStagingOnlySsmException() {
        String readme = readOrEmpty(README);

        // 判定断言：SSM 端口转发例外必须注明「仅限 staging」（PRD §10 可验收交付点）
        assertThat(readme).as("deploy/README.md 必须写明 SSM 端口转发例外「仅限 staging」").contains("仅限 staging");

        assertThat(readme).as("内网-only 限制").contains("内网");
        assertThat(readme).as("三条例外之一：持有 SSM 权限的 IAM 主体").contains("IAM");
        assertThat(readme).as("三条例外之二：实例无公网入站").contains("公网");
        assertThat(readme).as("三条例外之三：只用 AWS-StartPortForwardingSession 转发到本机 127.0.0.1:8080")
                .contains("AWS-StartPortForwardingSession").contains("127.0.0.1:8080");
        assertThat(readme).as("本地端口固定 localPortNumber=18080").contains("localPortNumber").contains("18080");
        assertThat(readme).as("原句写入（OPS-2 断言依据）").contains("staging 只放测试数据，不是正式环境");
        assertThat(readme).as("此例外不适用于正式环境").contains("不适用于正式环境");

        // 部署流程：git pull → compose up → curl 冒烟 → 迁移失败回退上一个 commit 再以新增迁移修复（按首次出现的顺序）
        List<String> steps = List.of("git pull", "docker compose up -d --build", "curl -fsS 127.0.0.1:8080/api/todos",
                "上一个 commit", "新增迁移");
        int previous = -1;
        for (String step : steps) {
            int at = readme.indexOf(step, previous + 1);
            assertThat(at).as("部署流程步骤「%s」必须出现，且在上一步之后", step).isGreaterThan(previous);
            previous = at;
        }
        assertThat(readme).as("迁移失败时回退").contains("回退");
    }

    // ------------------------------------------------------------------ AC-4

    @Test
    @DisplayName("[P1] AC-4 api 经 staging profile 与 SPRING_DATASOURCE_* 插值读取凭据，.env.example 只有占位，deploy/.env 被忽略，compose/Dockerfile 无字面密码")
    void credentialsComeFromEnvAndStagingProfile() {
        Map<String, Object> services = services();
        Map<String, Object> api = map(services.get("api"));
        Map<String, String> apiEnv = env(api);
        List<String> apiDocker = nonCommentLines(API_DOCKERFILE);
        Optional<String> dockerProfile = dockerfileEnv(apiDocker, "SPRING_PROFILES_ACTIVE");

        // 判定断言：api 以 staging profile 运行（compose 环境变量或 Dockerfile ENV，二选一，story T1.3）
        Optional<String> profile = Optional.ofNullable(apiEnv.get("SPRING_PROFILES_ACTIVE")).or(() -> dockerProfile);
        assertThat(profile).as("api 必须以 SPRING_PROFILES_ACTIVE=staging 运行（compose api.environment 或 Dockerfile ENV）")
                .hasValue("staging");
        if (apiEnv.containsKey("SPRING_PROFILES_ACTIVE") && dockerProfile.isPresent()) {
            assertThat(dockerProfile).as("compose 与 Dockerfile 两处都写 profile 时值必须一致").hasValue(apiEnv.get("SPRING_PROFILES_ACTIVE"));
        }

        Map<String, String> envExample = dotenv(readOrEmpty(ENV_EXAMPLE));
        assertThat(Files.isRegularFile(ENV_EXAMPLE)).as("提交 deploy/.env.example").isTrue();
        boolean usesEnvFile = api.containsKey("env_file");
        for (String key : List.of("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD")) {
            if (apiEnv.containsKey(key)) {
                String value = apiEnv.get(key);
                if (key.endsWith("_URL")) {
                    assertThat(value).as("%s 指向 compose 网络里的 db:5432，库名经插值", key)
                            .startsWith("jdbc:postgresql://db:5432/").contains("${");
                } else {
                    assertThat(value).as("%s 必须是 ${…} 插值（凭据只在 deploy/.env）", key).matches(PURE_INTERPOLATION);
                }
            } else {
                assertThat(usesEnvFile).as("%s 不在 api.environment 时，api 必须用 env_file 读 deploy/.env", key).isTrue();
                assertThat(envExample).as("%s 经 env_file 读取时，.env.example 必须列出这个键", key).containsKey(key);
            }
        }

        for (Map.Entry<String, String> e : envExample.entrySet()) {
            if (e.getKey().contains("PASSWORD")) {
                assertThat(e.getValue()).as(".env.example 的 %s 只能留空或写明显占位（如 change-me），不得出现真实密码", e.getKey())
                        .matches(PASSWORD_PLACEHOLDER);
            }
        }
        assertThat(envExample).as(".env.example 列出 postgres 镜像要的键").containsKeys("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD");

        assertThat(lines(readOrEmpty(GITIGNORE))).as("todoapp-api/.gitignore 忽略 deploy/.env（BE-0，只读核对）")
                .contains("deploy/.env");

        for (Map.Entry<String, Object> svc : services.entrySet()) {
            for (Map.Entry<String, String> e : env(map(svc.getValue())).entrySet()) {
                if (e.getKey().contains("PASSWORD")) {
                    assertThat(e.getValue()).as("%s.environment.%s 必须是 ${…} 插值，不得写字面密码", svc.getKey(), e.getKey())
                            .matches(PURE_INTERPOLATION);
                }
            }
        }
        assertThat(apiDocker).as("todoapp-api/Dockerfile 不写任何密码").noneMatch(l -> l.contains("PASSWORD"));
    }

    // ================================================================== helpers

    private static Path moduleRoot() {
        // surefire 设置 basedir；IDE 里运行时退回工作目录（与 StagingBoundaryTest 写法一致）
        String basedir = System.getProperty("basedir");
        Path root = Path.of(basedir != null ? basedir : System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(root.resolve("src/test/java/com/todoapp"))) {
            throw new IllegalStateException("找不到 todoapp-api 模块根（src/test/java/com/todoapp），当前：" + root);
        }
        return root;
    }

    private static String readOrEmpty(Path file) {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败：" + file, e);
        }
    }

    private static List<String> lines(String text) {
        return text.isEmpty() ? List.of() : text.lines().map(String::strip).toList();
    }

    /** 去掉空行与 {@code #} 开头的整行注释（Dockerfile 用）。 */
    private static List<String> nonCommentLines(Path file) {
        return lines(readOrEmpty(file)).stream().filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }

    private static List<String> fromLines(List<String> dockerfile) {
        return dockerfile.stream().filter(l -> l.regionMatches(true, 0, "FROM ", 0, 5)).toList();
    }

    private static int indexOfMatching(List<String> lines, Pattern pattern) {
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).matches()) {
                return i;
            }
        }
        return -1;
    }

    /** Dockerfile 里 {@code ENV KEY=VALUE} 或 {@code ENV KEY VALUE}。 */
    private static Optional<String> dockerfileEnv(List<String> dockerfile, String key) {
        Pattern p = Pattern.compile("^(?i:ENV)\\s+.*?\\b" + Pattern.quote(key) + "(?:=|\\s+)\"?([^\"\\s]+)\"?.*$");
        for (String line : dockerfile) {
            Matcher m = p.matcher(line);
            if (m.matches()) {
                return Optional.of(m.group(1));
            }
        }
        return Optional.empty();
    }

    private static Map<String, Object> compose() {
        String text = readOrEmpty(COMPOSE);
        if (text.isEmpty()) {
            return Map.of();
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        return map(loaded);
    }

    private static Map<String, Object> services() {
        return map(compose().get("services"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<Object, Object>) m).forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    /** environment 的 map 形式与 {@code KEY=VALUE} 列表形式统一成 Map。 */
    private static Map<String, String> env(Map<String, Object> service) {
        Object raw = service.get("environment");
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?>) {
            map(raw).forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        } else {
            for (Object item : list(raw)) {
                String s = String.valueOf(item);
                int eq = s.indexOf('=');
                out.put(eq < 0 ? s : s.substring(0, eq), eq < 0 ? "" : s.substring(eq + 1));
            }
        }
        return out;
    }

    private static Map<String, String> dotenv(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : lines(text)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                out.put(line.substring(0, eq).strip(), value);
            }
        }
        return out;
    }

    private static String healthcheckTest(Map<String, Object> service) {
        Object test = map(service.get("healthcheck")).get("test");
        if (test instanceof List<?> l) {
            return String.join(" ", l.stream().map(String::valueOf).toList());
        }
        return test == null ? "" : String.valueOf(test);
    }

    /** 卷挂载统一成 {@code source:target}（短语法去掉 {@code :ro} 之类的选项；长语法取 source / target）。 */
    private static List<String> volumeMounts(Map<String, Object> service) {
        List<String> out = new ArrayList<>();
        for (Object v : list(service.get("volumes"))) {
            if (v instanceof Map<?, ?>) {
                Map<String, Object> m = map(v);
                out.add(m.get("source") + ":" + m.get("target"));
            } else {
                String[] parts = String.valueOf(v).split(":");
                out.add(parts.length >= 2 ? parts[0] + ":" + parts[1] : parts[0]);
            }
        }
        return out;
    }

    private static String buildContext(Map<String, Object> service) {
        Object build = service.get("build");
        if (build instanceof Map<?, ?>) {
            Object ctx = map(build).get("context");
            return ctx == null ? null : String.valueOf(ctx);
        }
        return build == null ? null : String.valueOf(build);
    }

    private static List<String> dependsOnNames(Map<String, Object> service) {
        Object deps = service.get("depends_on");
        if (deps instanceof Map<?, ?>) {
            return new ArrayList<>(map(deps).keySet());
        }
        return list(deps).stream().map(String::valueOf).toList();
    }

    private static Optional<String> dependsOnCondition(Map<String, Object> service, String dependency) {
        Object condition = map(map(service.get("depends_on")).get(dependency)).get("condition");
        return Optional.ofNullable(condition).map(String::valueOf);
    }

    /** ports 统一成 {@code host_ip:published:target}（短语法原样；长语法拼接，缺 host_ip 时写空串以便断言失败可读）。 */
    private static List<String> publishedPorts(Object service) {
        List<String> out = new ArrayList<>();
        for (Object p : list(map(service).get("ports"))) {
            if (p instanceof Map<?, ?>) {
                Map<String, Object> m = map(p);
                Object hostIp = m.get("host_ip");
                out.add((hostIp == null ? "" : hostIp) + ":" + m.get("published") + ":" + m.get("target"));
            } else {
                out.add(String.valueOf(p));
            }
        }
        return out;
    }

    private static void collectScalars(Object node, List<String> out) {
        if (node instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                out.add(String.valueOf(k));
                collectScalars(v, out);
            });
        } else if (node instanceof List<?> l) {
            l.forEach(v -> collectScalars(v, out));
        } else if (node != null) {
            out.add(String.valueOf(node));
        }
    }
}
