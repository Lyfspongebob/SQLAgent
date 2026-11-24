package com.example.sqlagent.config;

import cn.dev33.satoken.exception.NotLoginException;
import com.example.sqlagent.dto.ApiResponse;
import com.example.sqlagent.error.AppException;
import com.example.sqlagent.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常出口。
 *
 * <p>Controller 不再重复捕获 RuntimeException；
 * 所有可预期异常都在这里映射为稳定的错误码、HTTP 状态和 requestId。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotLogin(
            NotLoginException exception,
            HttpServletRequest request
    ) {
        return build(
                ErrorCode.NOT_LOGGED_IN,
                ErrorCode.NOT_LOGGED_IN.defaultMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(
            AppException exception,
            HttpServletRequest request
    ) {
        if (exception.errorCode().httpStatus() >= 500) {
            log.error(
                    "业务异常: errorCode={}, message={}",
                    exception.errorCode(), exception.getMessage(), exception
            );
        } else {
            log.warn(
                    "业务异常: errorCode={}, message={}",
                    exception.errorCode(), exception.getMessage()
            );
        }

        return build(
                exception.errorCode(),
                exception.getMessage(),
                request,
                exception.details()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                details.put(error.getField(), error.getDefaultMessage())
        );

        return build(
                ErrorCode.REQUEST_VALIDATION_FAILED,
                "请求参数校验失败",
                request,
                details
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation ->
                details.put(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()
                )
        );

        return build(
                ErrorCode.REQUEST_VALIDATION_FAILED,
                "请求参数校验失败",
                request,
                details
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return build(
                ErrorCode.REQUEST_VALIDATION_FAILED,
                "请求体格式不正确",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        return build(
                ErrorCode.ACCESS_DENIED,
                ErrorCode.ACCESS_DENIED.defaultMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDatabaseError(
            DataAccessException exception,
            HttpServletRequest request
    ) {
        log.error("数据库访问异常", exception);
        return build(
                ErrorCode.DATABASE_ACCESS_FAILED,
                ErrorCode.DATABASE_ACCESS_FAILED.defaultMessage(),
                request,
                Map.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error("未处理异常", exception);
        return build(
                ErrorCode.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_SERVER_ERROR.defaultMessage(),
                request,
                Map.of()
        );
    }

    private ResponseEntity<ApiResponse<Void>> build(
            ErrorCode errorCode,
            String message,
            HttpServletRequest request,
            Map<String, Object> details
    ) {
        String requestId = requestId(request);
        return ResponseEntity
                .status(HttpStatus.valueOf(errorCode.httpStatus()))
                .body(ApiResponse.error(errorCode, message, requestId, details));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        );
        return value == null
                ? request.getHeader("X-Request-Id")
                : value.toString();
    }
}
