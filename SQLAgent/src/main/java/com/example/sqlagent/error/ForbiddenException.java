package com.example.sqlagent.error;

public class ForbiddenException extends AppException {

    public ForbiddenException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
