package com.modu.backend.domain.auth.jwt;

import com.modu.backend.domain.auth.exception.AuthErrorCode;
import com.modu.backend.global.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private JwtProvider jwtProvider;
    private JwtProvider expiredJwtProvider;

    @BeforeEach
    void setUp() {
        // 정상 토큰용 (만료 1시간)
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-must-be-at-least-32-bytes!!");
        properties.setAccessTokenExpiration(3600000L);
        properties.setRefreshTokenExpiration(1209600000L);
        jwtProvider = new JwtProvider(properties);

        // 만료 토큰 테스트용 (만료 -1초 → 생성 즉시 만료)
        JwtProperties expiredProperties = new JwtProperties();
        expiredProperties.setSecret("test-secret-key-must-be-at-least-32-bytes!!");
        expiredProperties.setAccessTokenExpiration(-1000L);
        expiredProperties.setRefreshTokenExpiration(1209600000L);
        expiredJwtProvider = new JwtProvider(expiredProperties);
    }

    // ── Access Token ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Access Token 생성 후 userId 추출 성공")
    void Access_Token_생성_후_userId_추출_성공() {
        // given
        Long userId = 1L;

        // when
        String token = jwtProvider.generateAccessToken(userId);
        Long extractedUserId = jwtProvider.getUserIdFromToken(token);

        // then
        assertThat(extractedUserId).isEqualTo(userId);
    }

    @Test
    @DisplayName("만료된 토큰 검증 시 EXPIRED_TOKEN 예외 발생")
    void 만료된_토큰_검증_시_EXPIRED_TOKEN_예외() {
        // given
        String expiredToken = expiredJwtProvider.generateAccessToken(1L);

        // when & then
        assertThatThrownBy(() -> jwtProvider.validateToken(expiredToken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.EXPIRED_TOKEN));
    }

    @Test
    @DisplayName("위조된 토큰 검증 시 INVALID_TOKEN 예외 발생")
    void 위조된_토큰_검증_시_INVALID_TOKEN_예외() {
        // given
        String invalidToken = "invalid.token.value";

        // when & then
        assertThatThrownBy(() -> jwtProvider.validateToken(invalidToken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_TOKEN));
    }

    @Test
    @DisplayName("다른 secret으로 서명된 토큰 검증 시 INVALID_TOKEN 예외 발생")
    void 다른_secret으로_서명된_토큰_검증_시_INVALID_TOKEN_예외() {
        // given - 다른 secret으로 만든 JwtProvider
        JwtProperties otherProperties = new JwtProperties();
        otherProperties.setSecret("completely-different-secret-key-32bytes!!");
        otherProperties.setAccessTokenExpiration(3600000L);
        otherProperties.setRefreshTokenExpiration(1209600000L);
        JwtProvider otherProvider = new JwtProvider(otherProperties);

        String tokenFromOtherProvider = otherProvider.generateAccessToken(1L);

        // when & then
        assertThatThrownBy(() -> jwtProvider.validateToken(tokenFromOtherProvider))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_TOKEN));
    }

    // ── Refresh Token ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("동일 입력값에 대한 hashToken 결과 일관성")
    void 동일_입력값_hashToken_결과_일관성() {
        // given
        String token = "some-refresh-token-uuid";

        // when
        String hash1 = jwtProvider.hashToken(token);
        String hash2 = jwtProvider.hashToken(token);

        // then
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("다른 입력값에 대한 hashToken 결과 상이")
    void 다른_입력값_hashToken_결과_상이() {
        // given
        String token1 = "refresh-token-uuid-1";
        String token2 = "refresh-token-uuid-2";

        // when
        String hash1 = jwtProvider.hashToken(token1);
        String hash2 = jwtProvider.hashToken(token2);

        // then
        assertThat(hash1).isNotEqualTo(hash2);
    }

    // ── 쿠키 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("로그인 쿠키 보안 설정 확인 (HttpOnly, Secure, SameSite)")
    void 로그인_쿠키_보안_설정_확인() {
        // given
        String token = jwtProvider.generateAccessToken(1L);

        // when
        ResponseCookie cookie = jwtProvider.createAccessTokenCookie(token);

        // then
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isPositive();
    }

    @Test
    @DisplayName("로그아웃 쿠키 maxAge가 0 (즉시 만료)")
    void 로그아웃_쿠키_maxAge가_0() {
        // when
        ResponseCookie accessCookie = jwtProvider.expireAccessTokenCookie();
        ResponseCookie refreshCookie = jwtProvider.expireRefreshTokenCookie();

        // then
        assertThat(accessCookie.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(refreshCookie.getMaxAge()).isEqualTo(Duration.ZERO);
    }
}
