package com.example.sqlagent.error;

import java.util.Map;

public class ValidationException extends AppException {

    public ValidationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public ValidationException(
            ErrorCode errorCode,
            String message,
            Map<String, Object> details,
            Throwable cause
    ) {
        super(errorCode, message, details, cause);
    }
}
