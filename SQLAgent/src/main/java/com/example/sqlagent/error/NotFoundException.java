package com.example.sqlagent.error;

public class NotFoundException extends AppException {

    public NotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
