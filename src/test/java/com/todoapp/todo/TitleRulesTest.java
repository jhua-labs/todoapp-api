package com.todoapp.todo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.todoapp.common.error.ValidationException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 2.2 T1.4（AC-2，AD-7）：{@link TitleRules} 纯单元测试，不起 Spring。
 *
 * <p>读共享用例表 {@code ../docs/contracts/title-cases.json}（相对 todoapp-api basedir，即 surefire 工作目录）；
 * 文件不存在时测试失败，不跳过。另断言代码里的裁剪集合与表里的 {@code trimCodePoints} 完全相等，防两份表漂移。
 */
@DisplayName("Story 2.2 TitleRules 标题规则（AD-7）")
class TitleRulesTest {

    private static final Path TITLE_CASES = Path.of("..", "docs", "contracts", "title-cases.json");

    private static JsonNode table() throws Exception {
        return JsonMapper.builder().build().readTree(Files.readString(TITLE_CASES, UTF_8));
    }

    @TestFactory
    @DisplayName("[P0] AC-2 BE-P2/BE-P3/BE-P4/BE-P5 title-cases.json 17 条用例在 TitleRules.normalize 上成立")
    List<DynamicTest> sharedCases() throws Exception {
        JsonNode cases = table().get("cases");
        assertThat(cases.size()).as("title-cases.json 共 17 条").isEqualTo(17);
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            String name = c.get("name").asString();
            String input = input(c);
            boolean valid = c.get("valid").asBoolean();
            String expected = c.has("trimmed")
                    ? c.get("trimmed").asString()
                    : c.has("repeat")
                            ? c.get("repeat").get("unit").asString().repeat(c.get("repeat").get("count").asInt())
                            : null;
            tests.add(DynamicTest.dynamicTest(name, () -> {
                if (valid) {
                    assertThat(TitleRules.normalize(input)).as("用例 %s", name).isEqualTo(expected);
                } else {
                    assertThatThrownBy(() -> TitleRules.normalize(input)).as("用例 %s", name)
                            .isInstanceOf(ValidationException.class);
                }
            }));
        }
        return tests;
    }

    @Test
    @DisplayName("[P0] AC-2 AD-7 代码里的裁剪集合与 title-cases.json 的 trimCodePoints 完全相等（25 个码点）")
    void trimSetMatchesContract() throws Exception {
        Set<Integer> fromJson = new LinkedHashSet<>();
        for (JsonNode cp : table().get("trimCodePoints")) {
            fromJson.add(Integer.parseInt(cp.asString().substring("U+".length()), 16));
        }
        assertThat(fromJson).hasSize(25);
        assertThat(TitleRules.TRIM_CODE_POINTS).containsExactlyInAnyOrderElementsOf(fromJson);
    }

    @Test
    @DisplayName("[P0] AC-2 BE-P3/BE-P5 null、U+0085、孤立低代理项、200/201 码点边界")
    void edgeCases() {
        assertThatThrownBy(() -> TitleRules.normalize(null)).isInstanceOf(ValidationException.class);
        // U+0085（NEL）不在裁剪表里：不被裁掉（Java Character.isWhitespace / strip 与 ECMAScript 不同）
        assertThat(TitleRules.normalize("\u0085x")).isEqualTo("\u0085x");
        // 孤立低代理项、尾部孤立高代理项
        assertThatThrownBy(() -> TitleRules.normalize("a\udc00b")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TitleRules.normalize("ab\ud83d")).isInstanceOf(ValidationException.class);
        // 裁剪表里的码点只在两端裁，中间保留
        assertThat(TitleRules.normalize("\ufeffa\u3000b\u2028")).isEqualTo("a\u3000b");
        // 长度在裁剪之后计：两端空白不计入 200
        assertThat(TitleRules.normalize("\t" + "字".repeat(200) + "\u3000")).hasSize(200);
        assertThatThrownBy(() -> TitleRules.normalize("😀".repeat(201))).isInstanceOf(ValidationException.class);
        // 不做规范化：组合字符序列原样保留（e + U+0301 计 2 个码点）
        assertThat(TitleRules.normalize("e\u0301")).isEqualTo("e\u0301");
    }

    /** 用例输入：{@code input}，或 {@code repeat.unit × repeat.count}（有 {@code pad} 时两端各加一份）。 */
    private static String input(JsonNode c) {
        if (c.has("input")) {
            return c.get("input").asString();
        }
        JsonNode repeat = c.get("repeat");
        String core = repeat.get("unit").asString().repeat(repeat.get("count").asInt());
        String pad = c.has("pad") ? c.get("pad").asString() : "";
        return pad + core + pad;
    }
}
