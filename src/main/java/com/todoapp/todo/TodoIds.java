package com.todoapp.todo;

import java.util.UUID;
import java.util.regex.Pattern;

import com.todoapp.common.error.NotFoundException;

/**
 * 路径 id 的严格解析（AD-8、PRD §11.4、契约 {@code parameters.id}）：只认小写规范 UUID 串，
 * 其余一律按「不存在」处理 → 404 {@code NOT_FOUND}（不因格式返回 400，不泄露 id 形态）。
 * PATCH（Story 3.1）与 DELETE（Story 3.2）共用 {@link #parse}。
 *
 * <p>纯静态工具，不是 Spring bean：{@code TodoService} 的构造器只接收 {@code TodoRepository} 与 {@code Clock}（AD-2）。
 */
public final class TodoIds {

    /** 小写规范 UUID：8-4-4-4-12 位十六进制，只认 a-f 小写。 */
    private static final Pattern CANONICAL =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private TodoIds() {
    }

    /**
     * 先用正则判形态，匹配后才交给 {@link UUID#fromString}：后者接受大写和 {@code 1-1-1-1-1} 这类不足位的串，
     * 不能用来判定（review-adversary H5）。异常 message 不拼入原始 id。
     */
    public static UUID parse(String raw) {
        if (raw == null || !CANONICAL.matcher(raw).matches()) {
            throw new NotFoundException("待办不存在");
        }
        return UUID.fromString(raw);
    }
}
