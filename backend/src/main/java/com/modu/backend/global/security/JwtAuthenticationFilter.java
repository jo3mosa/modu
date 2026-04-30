package com.modu.backend.global.security;

import com.modu.backend.domain.auth.jwt.JwtProvider;
import com.modu.backend.global.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 요청마다 쿠키에서 Access Token을 추출해 SecurityContext에 인증 정보 등록
 *
 * - 토큰이 없거나 유효하지 않으면 SecurityContext를 비워둠
 * - 이후 SecurityConfig의 authorizeHttpRequests에서 인증 필요 여부 판단
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractTokenFromCookie(request);

        if (token != null) {
            try {
                jwtProvider.validateToken(token);
                Long userId = jwtProvider.getUserIdFromToken(token);
                setAuthentication(userId);
            } catch (ApiException e) {
                // 유효하지 않은 토큰이면 인증 없이 통과, 이후 Security가 접근 제어
                log.debug("유효하지 않은 Access Token: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    /** 쿠키 배열에서 accessToken 값 추출 */
    private String extractTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;

        return Arrays.stream(request.getCookies())
                .filter(c -> "accessToken".equals(c.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElse(null);
    }

    /** userId를 principal로 하는 인증 객체를 SecurityContext에 등록 */
    private void setAuthentication(Long userId) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
