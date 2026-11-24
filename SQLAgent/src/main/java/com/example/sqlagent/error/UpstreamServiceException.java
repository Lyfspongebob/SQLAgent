package com.example.sqlagent.error;

public class UpstreamServiceException extends AppException {

    public UpstreamServiceException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public UpstreamServiceException(
            ErrorCode errorCode,
            String message,
            Throwable cause
    ) {
        super(errorCode, message, null, cause);
    }
}
