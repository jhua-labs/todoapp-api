package com.todoapp.todo;

import java.util.Set;

import com.todoapp.common.error.ValidationException;

/**
 * 标题规则（AD-7、PRD §3、§11.4）：前后端同一定义，后端只有这一份（Story 3.1 PATCH 复用 {@link #normalize}）。
 *
 * <p>纯静态工具，不是 Spring bean：{@code TodoService} 的构造器只接收 {@code TodoRepository} 与 {@code Clock}（AD-2）。
 *
 * <ul>
 *   <li>裁剪集合 = {@code docs/contracts/title-cases.json} 的 {@code trimCodePoints}（25 个码点，等价 ECMAScript trim），
 *       在这里显式列出；运行时不读该文件。不用 {@code String.trim()} / {@code strip()} / {@code Character.isWhitespace}——
 *       它们的空白集合与 ECMAScript 不同（例如 U+0085 不在表内、U+200B 不得被裁）。</li>
 *   <li>含 U+0000 或孤立代理项 → 拒绝。</li>
 *   <li>裁剪后按 Unicode 码点计长 1..200，不做 NFC/NFD 规范化（代理对计 1 个码点）。</li>
 * </ul>
 */
public final class TitleRules {

    static final int MAX_CODE_POINTS = 200;

    /** 与 {@code title-cases.json.trimCodePoints} 逐一对应；{@code TitleRulesTest} 断言两者完全相等。 */
    static final Set<Integer> TRIM_CODE_POINTS = Set.of(
            0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x00A0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
            0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF);

    private TitleRules() {
    }

    /**
     * 返回裁剪后的合法标题；不合法时抛 {@link ValidationException}（→ 400 {@code VALIDATION_ERROR}）。
     * 异常 message 不拼入标题（日志不记录标题）。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new ValidationException("标题不能为空");
        }
        // 先查非法码点：codePoints() 把成对代理合成一个码点，剩下落在 D800..DFFF 的就是孤立代理项
        boolean illegal = raw.codePoints().anyMatch(cp -> cp == 0 || (cp >= 0xD800 && cp <= 0xDFFF));
        if (illegal) {
            throw new ValidationException("标题包含不允许的字符");
        }
        String title = trim(raw);
        int length = title.codePointCount(0, title.length());
        if (length == 0) {
            throw new ValidationException("标题不能为空");
        }
        if (length > MAX_CODE_POINTS) {
            throw new ValidationException("标题不能超过 200 个字");
        }
        return title;
    }

    /** 按码点从两端裁掉 {@link #TRIM_CODE_POINTS}（表内全是 BMP 码点，但仍按码点前进以免拆开代理对）。 */
    private static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end) {
            int cp = s.codePointAt(start);
            if (!TRIM_CODE_POINTS.contains(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (!TRIM_CODE_POINTS.contains(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }
}
