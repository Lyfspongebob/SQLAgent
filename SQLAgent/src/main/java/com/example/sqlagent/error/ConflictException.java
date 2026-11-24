package com.example.sqlagent.error;

public class ConflictException extends AppException {

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
