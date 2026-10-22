package com.todoapp.common.error;

/** 目标不存在（含 id 格式非法）→ 404 {@code NOT_FOUND}（AD-8）。message 用中文，由 service 传入。 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
