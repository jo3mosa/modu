package com.modu.backend.domain.auth.service;

import com.modu.backend.domain.auth.exception.AuthErrorCode;
import com.modu.backend.domain.auth.jwt.JwtProperties;
import com.modu.backend.global.error.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

/**
 * SSE 단기 토큰 발급/검증 서비스
 *
 * [용도]
 * EventSource 가 Authorization 헤더를 보내지 못하므로, 헤더 JWT 로 인증된 사용자가
 * 짧은 수명의 SSE 전용 토큰을 발급받아 GET /orders/connect?token=... 의 query param 으로 전달한다.
 *
 * [Access Token 과의 분리]
 * 동일 secret 을 사용하되 audience("sse") 클레임으로 구분한다.
 * 일반 Access Token 으로 connect 호출 시 audience 검증에서 실패하여 401 응답.
 *
 * [수명]
 * 60초 — 토큰 발급 직후 즉시 EventSource 연결을 수립하는 시나리오 가정.
 * 일단 SSE 세션이 수립되면 토큰 재사용 불필요.
 */
@Service
public class SseTokenService {

    /** SSE 토큰 만료 시간 (초) */
    public static final long EXPIRATION_SECONDS = 60L;

    /** audience 클레임 값 — Access Token 과 SSE 토큰 분리용 */
    private static final String AUDIENCE_SSE = "sse";

    private final SecretKey secretKey;

    public SseTokenService(JwtProperties jwtProperties) {
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 사용자에게 60초 수명 SSE 토큰 발급
     */
    public String issue(Long userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + EXPIRATION_SECONDS * 1000L);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .audience().add(AUDIENCE_SSE).and()
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * SSE 토큰 검증 후 userId 반환
     * 누락/서명오류/만료/audience 불일치 시 SSE_TOKEN_INVALID 예외
     */
    public Long verifyAndGetUserId(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(AuthErrorCode.SSE_TOKEN_INVALID);
        }

        Claims claims = parseClaims(token);

        Set<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(AUDIENCE_SSE)) {
            throw new ApiException(AuthErrorCode.SSE_TOKEN_INVALID);
        }

        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new ApiException(AuthErrorCode.SSE_TOKEN_INVALID);
        }
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // 만료(ExpiredJwtException 포함) / 서명 오류 / 포맷 오류 — 모두 단일 코드로 매핑
            throw new ApiException(AuthErrorCode.SSE_TOKEN_INVALID);
        }
    }
}
