package com.example.sqlagent.error;

public class QueryExecutionException extends AppException {

    public QueryExecutionException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public QueryExecutionException(
            ErrorCode errorCode,
            String message,
            Throwable cause
    ) {
        super(errorCode, message, null, cause);
    }
}
