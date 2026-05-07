package com.modu.backend.global.security;

import com.modu.backend.domain.auth.exception.AuthErrorCode;
import com.modu.backend.domain.auth.jwt.JwtProvider;
import com.modu.backend.domain.auth.service.AccessTokenBlacklistService;
import com.modu.backend.global.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final AccessTokenBlacklistService accessTokenBlacklistService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractBearerToken(request);

        if (token != null) {
            try {
                jwtProvider.validateToken(token);
                if (accessTokenBlacklistService.isBlacklisted(token)) {
                    throw new ApiException(AuthErrorCode.INVALID_TOKEN);
                }
                Long userId = jwtProvider.getUserIdFromToken(token);
                setAuthentication(userId);
            } catch (ApiException e) {
                SecurityContextHolder.clearContext();
                request.setAttribute("authErrorCode", e.getErrorCode());
                log.debug("Invalid access token: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        if (!authorization.startsWith(BEARER_PREFIX)) {
            request.setAttribute("authErrorCode", AuthErrorCode.INVALID_TOKEN);
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            request.setAttribute("authErrorCode", AuthErrorCode.INVALID_TOKEN);
            return null;
        }
        return token;
    }

    private void setAuthentication(Long userId) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
