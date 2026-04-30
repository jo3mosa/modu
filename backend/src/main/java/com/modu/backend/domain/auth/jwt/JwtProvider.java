package com.modu.backend.domain.auth.jwt;

import com.modu.backend.domain.auth.exception.AuthErrorCode;
import com.modu.backend.global.error.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 액세스 토큰 생성/검증 및 Refresh Token 관리를 담당하는 컴포넌트
 *
 * [토큰 전략]
 * - Access Token : JWT (HS256 서명), 쿠키에 저장, 만료 1시간
 * - Refresh Token: UUID 원문, 쿠키에 저장, SHA-256 해시만 DB에 보관, 만료 14일
 *
 * [쿠키 설정]
 * - HttpOnly: JavaScript에서 접근 불가 (XSS 방어)
 * - Secure  : HTTPS 환경에서만 전송 (로컬 개발 시 브라우저에 따라 동작 상이)
 * - SameSite=Strict: CSRF 방어
 */
@Slf4j
@Component
public class JwtProvider {

    private final SecretKey secretKey;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;

    public JwtProvider(JwtProperties properties) {
        // JWT 서명용 키는 최소 32바이트(256bit) 이상이어야 HS256에서 사용 가능
        this.secretKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = properties.getAccessTokenExpiration();
        this.refreshTokenExpiration = properties.getRefreshTokenExpiration();
    }

    // ── Access Token ────────────────────────────────────────────────────────────

    /**
     * userId를 subject로 하는 Access Token(JWT)을 생성
     * 필터에서 토큰을 파싱해 userId를 꺼내 SecurityContext에 저장
     */
    public String generateAccessToken(Long userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiration);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 토큰을 파싱해 userId를 반환
     * 만료 또는 유효하지 않은 토큰이면 ApiException을 던짐
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = parseClaims(token);
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 토큰 유효성 검사
     * 유효하지 않으면 ApiException(INVALID_TOKEN 또는 EXPIRED_TOKEN)을 던짐
     */
    public void validateToken(String token) {
        parseClaims(token);
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new ApiException(AuthErrorCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(AuthErrorCode.INVALID_TOKEN);
        }
    }

    // ── Refresh Token ───────────────────────────────────────────────────────────

    /**
     * UUID 기반 Refresh Token 원문을 생성한다.
     * 원문은 쿠키에만 저장하고, DB에는 hashToken()을 통한 해시값만 저장한다.
     */
    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * Refresh Token 원문을 SHA-256으로 해싱한다.
     * DB 저장 및 조회 시 해시값을 사용해 원문 노출을 방지한다.
     */
    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    // ── 쿠키 생성 ───────────────────────────────────────────────────────────────

    /** 로그인 성공 시 Access Token 쿠키를 생성한다. */
    public ResponseCookie createAccessTokenCookie(String token) {
        return buildCookie("accessToken", token, accessTokenExpiration / 1000);
    }

    /** 로그인 성공 시 Refresh Token 쿠키를 생성한다. */
    public ResponseCookie createRefreshTokenCookie(String token) {
        return buildCookie("refreshToken", token, refreshTokenExpiration / 1000);
    }

    /** 로그아웃 시 Access Token 쿠키를 만료시킨다. */
    public ResponseCookie expireAccessTokenCookie() {
        return buildCookie("accessToken", "", 0);
    }

    /** 로그아웃 시 Refresh Token 쿠키를 만료시킨다. */
    public ResponseCookie expireRefreshTokenCookie() {
        return buildCookie("refreshToken", "", 0);
    }

    private ResponseCookie buildCookie(String name, String value, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
