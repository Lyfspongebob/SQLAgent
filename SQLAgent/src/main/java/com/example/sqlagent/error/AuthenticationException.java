package com.example.sqlagent.error;

public class AuthenticationException extends AppException {

    public AuthenticationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
