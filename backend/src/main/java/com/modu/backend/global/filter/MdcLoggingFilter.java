package com.modu.backend.global.filter;

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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcLoggingFilter extends OncePerRequestFilter {

    private static final String TRACE_ID = "traceId";
    private static final String RESPONSE_HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = UUID.randomUUID().toString();
        MDC.put(TRACE_ID, traceId);
        // 클라이언트가 서버 로그와 직접 대조할 수 있도록 응답 헤더에도 포함
        response.setHeader(RESPONSE_HEADER_TRACE_ID, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 가상 스레드 환경에서 이전 요청의 컨텍스트가 오염되지 않도록 반드시 초기화
            MDC.clear();
        }
    }
}
