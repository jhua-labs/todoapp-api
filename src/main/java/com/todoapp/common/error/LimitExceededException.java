package com.todoapp.common.error;

/** 已达容量上限 → 409 {@code LIMIT_EXCEEDED}（AD-8、AD-10）。message 用中文，由 service 传入。 */
public class LimitExceededException extends RuntimeException {

    public LimitExceededException(String message) {
        super(message);
    }
}
