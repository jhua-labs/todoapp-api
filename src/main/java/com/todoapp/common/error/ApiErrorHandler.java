package com.todoapp.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 唯一的 {@code @RestControllerAdvice}（AD-8）。不继承 {@code ResponseEntityExceptionHandler}，
 * 只输出 {@code {"error":{"code","message"}}}，code 只有 4 个。
 *
 * <ul>
 *   <li>领域异常的 message 由 service 传入（中文）；</li>
 *   <li>框架异常一律用固定中文文案，绝不把 {@code ex.getMessage()} 写进响应（Jackson 的消息会带输入片段，可能就是标题）；</li>
 *   <li>4xx 只在 DEBUG 记异常类名，不记消息与请求体；500 用 ERROR 记堆栈到 stdout，响应里不带堆栈。</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    static final String NOT_FOUND = "NOT_FOUND";
    static final String LIMIT_EXCEEDED = "LIMIT_EXCEEDED";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    static final String MSG_BAD_BODY = "请求体格式不正确";
    static final String MSG_UNSUPPORTED_MEDIA_TYPE = "请求体必须是 JSON";
    static final String MSG_METHOD_NOT_SUPPORTED = "不支持的请求方法";
    static final String MSG_NO_ROUTE = "资源不存在";
    static final String MSG_INTERNAL = "服务器内部错误";

    /** 契约错误体：{@code {"error":{"code","message"}}}。 */
    public record ErrorBody(Error error) {

        public record Error(String code, String message) {
        }
    }

    // —— 领域异常（service 抛出，message 为中文） ——

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ErrorBody> validation(ValidationException ex) {
        return body(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, ex.getMessage(), ex);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorBody> notFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage(), ex);
    }

    @ExceptionHandler(LimitExceededException.class)
    ResponseEntity<ErrorBody> limitExceeded(LimitExceededException ex) {
        return body(HttpStatus.CONFLICT, LIMIT_EXCEEDED, ex.getMessage(), ex);
    }

    // —— 框架异常（固定文案） ——

    /** 畸形 JSON、缺请求体、Jackson 类型不符 / 强转被拒。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, MSG_BAD_BODY, ex);
    }

    /** 非 JSON Content-Type（框架默认 415）。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return body(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, MSG_UNSUPPORTED_MEDIA_TYPE, ex);
    }

    /** 不支持的方法（框架默认 405）；契约 ValidationError 明确包含这一项。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorBody> methodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return body(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, MSG_METHOD_NOT_SUPPORTED, ex);
    }

    /** /api 下的未知路由（任何方法）。 */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ErrorBody> noRoute(Exception ex) {
        return body(HttpStatus.NOT_FOUND, NOT_FOUND, MSG_NO_ROUTE, ex);
    }

    /** 其他一切 → 500，不暴露堆栈与异常消息。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception ex) {
        log.error("未处理的异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(new ErrorBody.Error(INTERNAL_ERROR, MSG_INTERNAL)));
    }

    private static ResponseEntity<ErrorBody> body(HttpStatus status, String code, String message, Exception ex) {
        if (log.isDebugEnabled()) {
            log.debug("{} {} ← {}", status.value(), code, ex.getClass().getName());
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(new ErrorBody.Error(code, message)));
    }
}
