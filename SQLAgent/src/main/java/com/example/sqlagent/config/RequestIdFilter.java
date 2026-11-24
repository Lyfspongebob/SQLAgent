package com.example.sqlagent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 为每个请求生成稳定 requestId。
 *
 * <p>requestId 同时写入请求属性、响应头和 MDC，
 * 让前端错误提示与后端日志可以关联。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE =
            RequestIdFilter.class.getName() + ".requestId";

    private static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID =
            Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request);

        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put("requestId", requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String provided = request.getHeader(HEADER);
        if (provided != null && SAFE_REQUEST_ID.matcher(provided).matches()) {
            return provided;
        }
        return UUID.randomUUID().toString();
    }
}
