package com.example.sqlagent.error;

import java.util.Map;

/**
 * 所有可预期业务异常的基类。
 *
 * <p>异常本身携带稳定错误码和可选结构化详情，
 * 由 GlobalExceptionHandler 统一转换成 ApiResponse。</p>
 */
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public AppException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), Map.of(), null);
    }

    public AppException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of(), null);
    }

    public AppException(
            ErrorCode errorCode,
            String message,
            Map<String, Object> details,
            Throwable cause
    ) {
        super(message == null || message.isBlank()
                ? errorCode.defaultMessage()
                : message, cause);
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}
