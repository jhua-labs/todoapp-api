package com.todoapp.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.todoapp.common.error.NotFoundException;

/**
 * Story 3.1 T1.4：{@link TodoIds#parse} 的纯单元测试（AD-8、§11.4、契约 parameters.id）。
 * 只认小写规范 UUID；其余形态一律按「不存在」处理（404），不因格式返回 400。
 */
@DisplayName("Story 3.1 TodoIds.parse（路径 id 严格解析）")
class TodoIdsTest {

    private static final String LOWER = "0b9a7c1e-1d2f-4a3b-8c4d-5e6f7a8b9c0d";

    @Test
    @DisplayName("[P0] BE-U8 合法小写规范 UUID → 返回相等的 UUID")
    void parsesCanonicalLowercase() {
        assertThat(TodoIds.parse(LOWER)).isEqualTo(UUID.fromString(LOWER));
        UUID random = UUID.randomUUID();
        assertThat(TodoIds.parse(random.toString())).isEqualTo(random);
    }

    @TestFactory
    @DisplayName("[P0] BE-U8 非规范形态（大写、混合大小写、不足位、去连字符、带花括号、首尾空格、35/37 位、非十六进制、空串、null）→ NotFoundException")
    Stream<DynamicTest> rejectsNonCanonical() {
        String[] invalid = {
                LOWER.toUpperCase(),
                "0B9a7c1e-1d2f-4a3b-8c4d-5e6f7a8b9c0d",
                "1-1-1-1-1",
                LOWER.replace("-", ""),
                "{" + LOWER + "}",
                " " + LOWER,
                LOWER + " ",
                LOWER.substring(1),
                LOWER + "0",
                LOWER.substring(0, 35) + "g",
                "0b9a7c1e_1d2f_4a3b_8c4d_5e6f7a8b9c0d",
                "abc",
                "",
                null,
        };
        return Arrays.stream(invalid).map(raw -> DynamicTest.dynamicTest("[" + raw + "]", () ->
                assertThatThrownBy(() -> TodoIds.parse(raw))
                        .isInstanceOf(NotFoundException.class)
                        .satisfies(ex -> {
                            if (raw != null && !raw.isEmpty()) {
                                assertThat(ex.getMessage()).as("message 不拼入原始 id").doesNotContain(raw);
                            }
                        })));
    }
}
