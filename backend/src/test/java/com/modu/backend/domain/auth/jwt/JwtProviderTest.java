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
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-must-be-at-least-32-bytes!!");
        properties.setAccessTokenExpiration(3600000L);
        properties.setRefreshTokenExpiration(1209600000L);
        jwtProvider = new JwtProvider(properties);

        JwtProperties expiredProperties = new JwtProperties();
        expiredProperties.setSecret("test-secret-key-must-be-at-least-32-bytes!!");
        expiredProperties.setAccessTokenExpiration(-1000L);
        expiredProperties.setRefreshTokenExpiration(1209600000L);
        expiredJwtProvider = new JwtProvider(expiredProperties);
    }

    @Test
    @DisplayName("access token contains user id")
    void accessTokenContainsUserId() {
        Long userId = 1L;

        String token = jwtProvider.generateAccessToken(userId);
        Long extractedUserId = jwtProvider.getUserIdFromToken(token);

        assertThat(extractedUserId).isEqualTo(userId);
    }

    @Test
    @DisplayName("expired access token throws EXPIRED_TOKEN")
    void expiredAccessTokenThrowsExpiredToken() {
        String expiredToken = expiredJwtProvider.generateAccessToken(1L);

        assertThatThrownBy(() -> jwtProvider.validateToken(expiredToken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.EXPIRED_TOKEN));
    }

    @Test
    @DisplayName("invalid access token throws INVALID_TOKEN")
    void invalidAccessTokenThrowsInvalidToken() {
        String invalidToken = "invalid.token.value";

        assertThatThrownBy(() -> jwtProvider.validateToken(invalidToken))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_TOKEN));
    }

    @Test
    @DisplayName("token signed with other secret throws INVALID_TOKEN")
    void tokenSignedWithOtherSecretThrowsInvalidToken() {
        JwtProperties otherProperties = new JwtProperties();
        otherProperties.setSecret("completely-different-secret-key-32bytes!!");
        otherProperties.setAccessTokenExpiration(3600000L);
        otherProperties.setRefreshTokenExpiration(1209600000L);
        JwtProvider otherProvider = new JwtProvider(otherProperties);

        String tokenFromOtherProvider = otherProvider.generateAccessToken(1L);

        assertThatThrownBy(() -> jwtProvider.validateToken(tokenFromOtherProvider))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_TOKEN));
    }

    @Test
    @DisplayName("remaining expiration millis is positive for valid token")
    void remainingExpirationMillisIsPositiveForValidToken() {
        String token = jwtProvider.generateAccessToken(1L);

        assertThat(jwtProvider.getRemainingExpirationMillis(token)).isPositive();
    }

    @Test
    @DisplayName("same refresh token hashes to same value")
    void sameRefreshTokenHashesToSameValue() {
        String token = "some-refresh-token-uuid";

        String hash1 = jwtProvider.hashToken(token);
        String hash2 = jwtProvider.hashToken(token);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("different refresh tokens hash to different values")
    void differentRefreshTokensHashToDifferentValues() {
        String token1 = "refresh-token-uuid-1";
        String token2 = "refresh-token-uuid-2";

        String hash1 = jwtProvider.hashToken(token1);
        String hash2 = jwtProvider.hashToken(token2);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("refresh token cookie uses secure attributes")
    void refreshTokenCookieUsesSecureAttributes() {
        ResponseCookie cookie = jwtProvider.createRefreshTokenCookie("refresh-token");

        assertThat(cookie.getName()).isEqualTo("refreshToken");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isPositive();
    }

    @Test
    @DisplayName("expired refresh cookie maxAge is zero")
    void expiredRefreshCookieMaxAgeIsZero() {
        ResponseCookie refreshCookie = jwtProvider.expireRefreshTokenCookie();

        assertThat(refreshCookie.getMaxAge()).isEqualTo(Duration.ZERO);
    }
}
