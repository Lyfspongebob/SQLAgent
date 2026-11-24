package com.example.sqlagent.dto;

import com.example.sqlagent.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private Integer code;
    private Boolean success;
    private String errorCode;
    private String message;
    private String requestId;
    private Map<String, Object> details;
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                200, true, null, "success", null, Map.of(), data
        );
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(
                200, true, null, message, null, Map.of(), data
        );
    }

    public static <T> ApiResponse<T> error(
            ErrorCode errorCode,
            String message,
            String requestId,
            Map<String, Object> details
    ) {
        return new ApiResponse<>(
                errorCode.httpStatus(),
                false,
                errorCode.name(),
                message == null || message.isBlank()
                        ? errorCode.defaultMessage()
                        : message,
                requestId,
                details == null ? Map.of() : Map.copyOf(details),
                null
        );
    }

    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(
                code,
                false,
                null,
                message,
                null,
                Map.of(),
                null
        );
    }

    public static <T> ApiResponse<T> error(String message) {
        return error(400, message);
    }

    public static <T> ApiResponse<T> unauthorized(String message) {
        return error(401, message);
    }

    public static <T> ApiResponse<T> forbidden(String message) {
        return error(403, message);
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return error(404, message);
    }
}
