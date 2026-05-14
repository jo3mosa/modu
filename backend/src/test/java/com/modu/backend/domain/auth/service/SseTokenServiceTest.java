package com.modu.backend.domain.auth.service;

import com.modu.backend.domain.auth.exception.AuthErrorCode;
import com.modu.backend.domain.auth.jwt.JwtProperties;
import com.modu.backend.global.error.ApiException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SseTokenService 단위 테스트
 *
 * 발급/검증/만료/audience 불일치/잘못된 형식 케이스 검증.
 */
class SseTokenServiceTest {

    private static final String SECRET = "test-secret-with-at-least-256-bits-for-hs256-algorithm!!";
    private SseTokenService service;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setAccessTokenExpiration(3_600_000L);
        props.setRefreshTokenExpiration(1_209_600_000L);
        service = new SseTokenService(props);
        secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("issue → verifyAndGetUserId 정상 흐름")
    void 발급_후_검증_성공() {
        String token = service.issue(42L);

        Long userId = service.verifyAndGetUserId(token);

        assertThat(userId).isEqualTo(42L);
    }

    @Test
    @DisplayName("토큰 null/blank 시 SSE_TOKEN_INVALID")
    void 토큰_누락_예외() {
        assertThatThrownBy(() -> service.verifyAndGetUserId(null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.SSE_TOKEN_INVALID));

        assertThatThrownBy(() -> service.verifyAndGetUserId(""))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> service.verifyAndGetUserId("   "))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("형식 오류 토큰 시 SSE_TOKEN_INVALID")
    void 형식_오류_예외() {
        assertThatThrownBy(() -> service.verifyAndGetUserId("not-a-jwt"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.SSE_TOKEN_INVALID));
    }

    @Test
    @DisplayName("audience 불일치 토큰(Access Token 등) 시 SSE_TOKEN_INVALID")
    void audience_불일치_예외() {
        // audience 없는(Access Token 형태) JWT 직접 생성
        Date now = new Date();
        String accessLikeToken = Jwts.builder()
                .subject("42")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60_000L))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> service.verifyAndGetUserId(accessLikeToken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.SSE_TOKEN_INVALID));
    }

    @Test
    @DisplayName("만료된 토큰 시 SSE_TOKEN_INVALID")
    void 만료_토큰_예외() {
        Date past = new Date(System.currentTimeMillis() - 60_000L);
        String expiredToken = Jwts.builder()
                .subject("42")
                .audience().add("sse").and()
                .issuedAt(new Date(past.getTime() - 60_000L))
                .expiration(past)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> service.verifyAndGetUserId(expiredToken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.SSE_TOKEN_INVALID));
    }
}
