package com.todoapp.common.error;

/** 输入不合法 → 400 {@code VALIDATION_ERROR}（AD-8）。message 用中文，由 service 传入。 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
