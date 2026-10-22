package com.todoapp.devscript;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story 4.1（DEV-1，STATIC）：{@code todoapp-api/scripts/dev.sh} 的静态断言（AD-14）。
 *
 * <p>只读脚本文本，外加 {@code bash -n} 语法检查（不执行脚本）。测试绝不运行 dev.sh、绝不启动后端 / 前端、
 * 绝不连 54329 或 8080：真实并起、监听地址、重启后数据仍在、Ctrl-C 后端口释放属于 DEV-2 手动冒烟（story T4）。
 *
 * <p>纯 JUnit：不带 Spring 注解、不产生 bean、没有 main 方法（{@code LocalProfileIsolationTest} 守住 src/test 只有一个 main）。
 * surefire 工作目录是 todoapp-api basedir，所以脚本路径写成相对的 {@code scripts/dev.sh}。
 *
 * <p>匹配规则（story T2.2）：除首行 shebang 外，只看「非注释行」——去掉首尾空白后以 {@code #} 开头的整行不参与匹配，
 * 避免中文注释里提到 {@code .local-pg}、{@code kill -9} 等字样造成误判；「不含某命令」一律用带词边界的正则。
 *
 * <p>红阶段：dev.sh 尚不存在。脚本缺失时 {@link #nonCommentLines()} 返回空列表，
 * 让每个叶子都在自己的判定断言处失败，而不是在读文件时抛 {@code NoSuchFileException}。
 */
@DisplayName("Story 4.1 DEV-1 dev.sh 静态断言")
class DevScriptTest {

    private static final Path SCRIPT = Path.of("scripts/dev.sh");

    /** 以脚本自身目录为基准：{@code NAME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"}（story T1.2）。 */
    private static final Pattern SCRIPT_DIR_ASSIGNMENT = Pattern.compile(
            "^(?:readonly\\s+|declare\\s+-r\\s+)?([A-Za-z_][A-Za-z0-9_]*)=\"?\\$\\(\\s*cd\\s+\"?\\$\\(\\s*dirname\\s+\"?\\$\\{BASH_SOURCE(?:\\[0])?}\"?\\s*\\)\"?\\s*&&\\s*pwd\\s*\\)\"?\\s*$");

    private static final Pattern BACKEND_LAUNCH_HINT = Pattern.compile("spring-boot:test-run");
    private static final Pattern FRONTEND_LAUNCH_HINT = Pattern.compile("\\bnpm\\s+--prefix\\b");
    private static final Pattern NODE_VERSION_CALL = Pattern.compile("\\bnode\\s+(?:-v|--version)\\b");
    private static final Pattern MAJOR_24_COMPARISON = Pattern.compile("(?:-ne|-eq|!=|==|=)\\s*\"?24\"?(?![0-9])");
    private static final Pattern NODE_PRESENCE_CHECK = Pattern.compile("\\b(?:command\\s+-v|type|hash|which)\\s+node\\b");

    // ------------------------------------------------------------------ AC-1

    @Test
    @DisplayName("[P1] AC-1 DEV-1 dev.sh 存在可执行、以脚本目录解析路径、先查 Node 24 再并行起后端（test-run）与前端（npm --prefix ../../todoapp-web run dev）")
    void devScriptStartsFullStackFromScriptDirAfterNodeCheck() throws Exception {
        // 判定断言：工作区根执行 todoapp-api/scripts/dev.sh 的前提——脚本存在且可执行
        assertThat(Files.isExecutable(SCRIPT))
                .as("scripts/dev.sh 必须存在且有可执行位（story T1.10，git mode 100755）")
                .isTrue();

        List<String> raw = Files.readAllLines(SCRIPT, StandardCharsets.UTF_8);
        assertThat(raw.get(0)).as("首行 shebang（story T1.1）").isEqualTo("#!/usr/bin/env bash");

        List<String> lines = nonCommentLines();
        String scriptDirVar = scriptDirVariable(lines)
                .orElseThrow(() -> new AssertionError(
                        "缺少以 BASH_SOURCE + dirname 解析脚本目录的赋值行，例如 SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\""));
        String dir = varRef(scriptDirVar);

        int backend = singleLineIndex(lines, BACKEND_LAUNCH_HINT, "后端启动行（spring-boot:test-run）");
        assertThat(lines.get(backend))
                .as("后端：脚本目录下的 mvnw，-f 指向脚本目录下的 pom.xml，后台运行 spring-boot:test-run（AD-14）")
                .matches("^\"?" + dir + "/\\.\\./mvnw\"?\\s+-f\\s+\"?" + dir + "/\\.\\./pom\\.xml\"?"
                        + "(?:\\s+-Dspring-boot\\.run\\.main-class=com\\.todoapp\\.local\\.LocalDevApplication)?"
                        + "\\s+spring-boot:test-run"
                        + "(?:\\s+-Dspring-boot\\.run\\.main-class=com\\.todoapp\\.local\\.LocalDevApplication)?"
                        + "\\s*&\\s*$");

        int frontend = singleLineIndex(lines, FRONTEND_LAUNCH_HINT, "前端启动行（npm --prefix）");
        assertThat(lines.get(frontend))
                .as("前端：npm --prefix <脚本目录>/../../todoapp-web run dev，后台运行（AD-14）")
                .matches("^npm\\s+--prefix\\s+\"?" + dir + "/\\.\\./\\.\\./todoapp-web\"?\\s+run\\s+dev\\s*&\\s*$");

        int firstLaunch = Math.min(backend, frontend);
        int nodeCall = firstIndex(lines, NODE_VERSION_CALL);
        int majorCheck = firstIndex(lines, MAJOR_24_COMPARISON);
        int presenceCheck = firstIndex(lines, NODE_PRESENCE_CHECK);
        assertThat(nodeCall).as("调用 node -v 取版本（story T1.3）").isNotNegative();
        assertThat(majorCheck).as("主版本与 24 比较（story T1.3）").isNotNegative();
        assertThat(presenceCheck).as("node 不存在时先给出中文报错（command -v node 等，story T1.3）").isNotNegative();
        assertThat(List.of(nodeCall, majorCheck, presenceCheck))
                .as("Node 检查必须全部出现在第一个后台启动（第 %d 个非注释行）之前", firstLaunch)
                .allMatch(i -> i < firstLaunch);

        assertThat(lines)
                .as("只能是 run dev：不得用 dev:mock（AD-12），不写死绝对路径")
                .noneMatch(l -> l.contains("dev:mock"))
                .noneMatch(l -> l.contains("/Users/"));
        assertThat(lines)
                .as("兼容 macOS 自带 bash 3.2.57：不用 mapfile/readarray、关联数组、${var,,}/${var^^}、&>>（story T1.1）")
                .noneMatch(l -> l.matches(".*\\b(?:mapfile|readarray)\\b.*"))
                .noneMatch(l -> l.matches(".*\\bdeclare\\s+-[a-zA-Z]*A.*"))
                .noneMatch(l -> l.matches(".*\\$\\{[^}]*(?:,,|\\^\\^)}.*"))
                .noneMatch(l -> l.contains("&>>"));

        Process syntax = new ProcessBuilder("bash", "-n", SCRIPT.toString()).redirectErrorStream(true).start();
        boolean finished = syntax.waitFor(10, TimeUnit.SECONDS);
        String syntaxOutput = new String(syntax.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as("bash -n 应在 10 秒内结束").isTrue();
        assertThat(syntax.exitValue()).as("bash -n scripts/dev.sh 语法检查（只做 -n，不执行）：%s", syntaxOutput).isZero();
    }

    // ------------------------------------------------------------------ AC-2

    @Test
    @DisplayName("[P2] AC-2 DEV-1 dev.sh 不改监听面：前端只 run dev 不追加 --host/--port，后端不覆盖 server.address/port，全文无 0.0.0.0")
    void devScriptKeepsLoopbackOnlyConfiguration() throws Exception {
        List<String> lines = nonCommentLines();

        // 判定断言：前端启动行恰好是 run dev，之后除后台符 & 外没有任何参数（禁 --host，AD-14）
        assertThat(lines)
                .as("恰有一行以 npm --prefix 启动前端，且 run dev 之后只有 &（不追加 --host、--port、-- --host 等）")
                .filteredOn(l -> FRONTEND_LAUNCH_HINT.matcher(l).find())
                .singleElement()
                .matches(l -> l.matches(".*\\brun\\s+dev\\s*&\\s*$"), "run dev 后只能跟 &");

        assertThat(lines)
                .as("不放宽监听地址 / 端口：host、port、strictPort 由 vite.config.ts 与 application.yaml 决定（AD-14）")
                .noneMatch(l -> l.matches(".*(?:^|\\s)--host\\b.*"))
                .noneMatch(l -> l.matches(".*(?:^|\\s)--port\\b.*"))
                .noneMatch(l -> l.contains("0.0.0.0"))
                .noneMatch(l -> l.matches(".*server[._-]address.*"))
                .noneMatch(l -> l.matches(".*server[._-]port.*"))
                .noneMatch(l -> l.matches(".*\\bSERVER_(?:ADDRESS|PORT)\\b.*"))
                .noneMatch(l -> l.matches(".*\\bstrictPort\\b.*"));
    }

    // ------------------------------------------------------------------ AC-3

    @Test
    @DisplayName("[P2] AC-3 DEV-1 dev.sh 不碰本地数据：后端只跑 test-run（无 clean、无 profiles 参数），不删 .local-pg、不写死 embedded 开关")
    void devScriptPreservesLocalDatabase() throws Exception {
        List<String> lines = nonCommentLines();

        // 判定断言：恰有一行后端启动，且它不带 clean 目标、不带 -Dspring-boot.run.profiles（local 由 LocalDevApplication 激活）
        assertThat(lines)
                .as("恰有一行后端启动（spring-boot:test-run），不带 clean、不带 -Dspring-boot.run.profiles（AD-14）")
                .filteredOn(l -> BACKEND_LAUNCH_HINT.matcher(l).find())
                .singleElement()
                .matches(l -> !l.matches(".*\\bclean\\b.*") && !l.contains("spring-boot.run.profiles"),
                        "无 clean、无 profiles 覆盖");

        assertThat(lines)
                .as("脚本不删除 / 移动 / 引用 .local-pg，不 mvn clean（story T1.9；重启后数据仍在，FR-8）")
                .noneMatch(l -> l.contains(".local-pg"))
                .noneMatch(l -> l.matches(".*(?:^|[\\s;&|(])rm\\s.*"))
                .noneMatch(l -> l.matches(".*(?:^|[\\s;&|(])mv\\s.*"))
                .noneMatch(l -> l.matches(".*(?:^|\\s)clean(?:\\s|$).*"));
        assertThat(lines)
                .as("brew 退路由调用方切换（TODOAPP_LOCAL_DB_EMBEDDED / MAVEN_OPTS），脚本不写死 embedded 开关、不发明新选项")
                .noneMatch(l -> l.matches(".*todoapp\\.local-db\\.embedded.*"))
                .noneMatch(l -> l.matches(".*\\bTODOAPP_LOCAL_DB_EMBEDDED\\s*=.*"))
                .noneMatch(l -> l.matches(".*\\bMAVEN_OPTS\\s*=.*"));
        assertThat(lines)
                .as("不做额外动作：不 npm install/ci、不跑测试、不 git（story T1.9）")
                .noneMatch(l -> l.matches(".*\\bnpm\\s+(?:--prefix\\s+\\S+\\s+)?(?:install|ci|i)\\b.*"))
                .noneMatch(l -> l.matches(".*(?:^|[\\s;&|(])git\\s.*"))
                .noneMatch(l -> l.matches(".*\\bmvnw?\\b.*\\btest\\b(?!-run).*"));
    }

    // ------------------------------------------------------------------ AC-4

    @Test
    @DisplayName("[P1] AC-4 DEV-1 dev.sh 中断时 trap INT/TERM，用 kill -TERM 一起停两个子进程，全文不出现 kill -9 / KILL / pkill")
    void devScriptStopsBothChildrenWithSigterm() throws Exception {
        List<String> lines = nonCommentLines();

        // 判定断言：trap 同时捕获 INT 与 TERM（Ctrl-C 与 kill <dev.sh pid> 都会触发清理）
        assertThat(trappedSignals(lines))
                .as("trap 捕获的信号（AD-14：用户中断 dev.sh 时一起停）")
                .contains("INT", "TERM");

        assertThat(lines)
                .as("对子进程发 SIGTERM：kill -TERM 或 kill -s TERM（含进程组形式 kill -TERM -- -\"$pid\"）")
                .anyMatch(l -> l.matches(".*\\bkill\\s+(?:-TERM|-s\\s+TERM|-SIGTERM)\\b.*"));
        assertThat(lines)
                .as("清理后 wait 子进程结束")
                .anyMatch(l -> l.matches(".*(?:^|[\\s;&|(])wait\\b.*"));
        assertThat(lines)
                .as("不得用 kill -9 / KILL / SIGKILL / pkill（AD-14）")
                .noneMatch(l -> l.matches(".*\\bkill\\s+-9\\b.*"))
                .noneMatch(l -> l.matches(".*\\b(?:SIG)?KILL\\b.*"))
                .noneMatch(l -> l.matches(".*\\bpkill\\b.*"));
        assertThat(lines)
                .as("bash 3.2 没有 wait -n：任一子进程退出的检测用 kill -0 轮询等写法（story T1.8）")
                .noneMatch(l -> l.matches(".*\\bwait\\s+-n\\b.*"));
    }

    // ------------------------------------------------------------------ helpers

    /** 非注释行（去掉首尾空白；跳过空行与以 # 开头的整行，含 shebang）。脚本不存在时返回空列表。 */
    private static List<String> nonCommentLines() throws IOException {
        if (!Files.exists(SCRIPT)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : Files.readAllLines(SCRIPT, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static Optional<String> scriptDirVariable(List<String> lines) {
        for (String line : lines) {
            Matcher m = SCRIPT_DIR_ASSIGNMENT.matcher(line);
            if (m.matches()) {
                return Optional.of(m.group(1));
            }
        }
        return Optional.empty();
    }

    /** {@code $NAME} 或 {@code ${NAME}} 的正则。 */
    private static String varRef(String name) {
        return "\\$(?:" + name + "\\b|\\{" + name + "})";
    }

    private static int singleLineIndex(List<String> lines, Pattern hint, String what) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (hint.matcher(lines.get(i)).find()) {
                hits.add(i);
            }
        }
        assertThat(hits).as("%s 应恰好一行", what).hasSize(1);
        return hits.get(0);
    }

    private static int firstIndex(List<String> lines, Pattern pattern) {
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find()) {
                return i;
            }
        }
        return -1;
    }

    /** 所有 trap 行末尾列出的信号名（去掉 SIG 前缀），例如 {@code trap cleanup INT TERM EXIT} → INT、TERM、EXIT。 */
    private static Set<String> trappedSignals(List<String> lines) {
        Set<String> signals = new LinkedHashSet<>();
        Pattern trailingSignals = Pattern.compile("((?:\\s+(?:SIG)?[A-Z]+)+)\\s*$");
        for (String line : lines) {
            if (!line.matches("^trap\\s+.*")) {
                continue;
            }
            Matcher m = trailingSignals.matcher(line);
            if (m.find()) {
                for (String token : m.group(1).trim().split("\\s+")) {
                    signals.add(token.startsWith("SIG") ? token.substring(3) : token);
                }
            }
        }
        return signals;
    }
}
