package com.example.sqlagent.config;

import com.example.sqlagent.dto.ApiResponse;
import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler();

    @Test
    void mapsBusinessExceptionToStableHttpAndErrorCode() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                "request-test-001"
        );

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAppException(
                        new ForbiddenException(
                                ErrorCode.DATA_SOURCE_ACCESS_DENIED,
                                "数据源不存在或无权使用"
                        ),
                        request
                );

        assertEquals(403, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(403, response.getBody().getCode());
        assertFalse(response.getBody().getSuccess());
        assertEquals(
                "DATA_SOURCE_ACCESS_DENIED",
                response.getBody().getErrorCode()
        );
        assertEquals("request-test-001", response.getBody().getRequestId());
    }

    @Test
    void hidesUnexpectedExceptionDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnexpected(
                        new IllegalStateException("数据库密码不应返回给用户"),
                        request
                );

        assertEquals(500, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(
                ErrorCode.INTERNAL_SERVER_ERROR.name(),
                response.getBody().getErrorCode()
        );
        assertEquals(
                ErrorCode.INTERNAL_SERVER_ERROR.defaultMessage(),
                response.getBody().getMessage()
        );
    }
}
