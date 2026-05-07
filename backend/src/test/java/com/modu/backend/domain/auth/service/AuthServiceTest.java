package com.modu.backend.domain.auth.service;

import com.modu.backend.domain.auth.client.KakaoOAuthClient;
import com.modu.backend.domain.auth.client.KakaoUserInfo;
import com.modu.backend.domain.auth.dto.LoginResponse;
import com.modu.backend.domain.auth.dto.TokenResponse;
import com.modu.backend.domain.auth.entity.RefreshToken;
import com.modu.backend.domain.auth.exception.AuthErrorCode;
import com.modu.backend.domain.auth.jwt.JwtProperties;
import com.modu.backend.domain.auth.jwt.JwtProvider;
import com.modu.backend.domain.auth.repository.RefreshTokenRepository;
import com.modu.backend.domain.investment.repository.InvestmentProfileRepository;
import com.modu.backend.domain.trading.repository.TradingRuleRepository;
import com.modu.backend.domain.user.entity.User;
import com.modu.backend.domain.user.repository.UserRepository;
import com.modu.backend.global.error.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock KakaoOAuthClient kakaoOAuthClient;
    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock JwtProvider jwtProvider;
    @Mock JwtProperties jwtProperties;
    @Mock InvestmentProfileRepository investmentProfileRepository;
    @Mock TradingRuleRepository tradingRuleRepository;
    @Mock AccessTokenBlacklistService accessTokenBlacklistService;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;

    @InjectMocks
    AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .provider("kakao")
                .providerId("123456789")
                .nickname("modu")
                .email("test@kakao.com")
                .build();
        ReflectionTestUtils.setField(testUser, "id", 1L);
    }

    private void mockTokenIssuance() {
        when(jwtProvider.generateAccessToken(anyLong())).thenReturn("access-token");
        when(jwtProvider.generateRefreshToken()).thenReturn("refresh-token");
        when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-refresh-token");
        when(jwtProperties.getRefreshTokenExpiration()).thenReturn(1209600000L);
        when(jwtProvider.createRefreshTokenCookie("refresh-token"))
                .thenReturn(ResponseCookie.from("refreshToken", "refresh-token").build());
    }

    private void mockOnboarding(boolean surveyCompleted, boolean ruleSetCompleted) {
        when(investmentProfileRepository.existsByUserId(1L)).thenReturn(surveyCompleted);
        when(tradingRuleRepository.existsByUserId(1L)).thenReturn(ruleSetCompleted);
    }

    @Test
    @DisplayName("social login creates user and returns access token in body")
    void socialLoginCreatesUserAndReturnsAccessTokenInBody() {
        KakaoUserInfo kakaoUserInfo = new KakaoUserInfo("123456789", "modu", "test@kakao.com");
        when(kakaoOAuthClient.getUserInfo("auth-code")).thenReturn(kakaoUserInfo);
        when(userRepository.findByProviderAndProviderId("kakao", "123456789")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        mockTokenIssuance();
        mockOnboarding(false, false);

        LoginResponse result = authService.socialLogin("kakao", "auth-code", response);

        verify(userRepository).save(any(User.class));
        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.userId()).isEqualTo(1L);
        verify(response).addHeader(eqSetCookie(), anyString());
    }

    @Test
    @DisplayName("social login reuses existing user")
    void socialLoginReusesExistingUser() {
        KakaoUserInfo kakaoUserInfo = new KakaoUserInfo("123456789", "modu", "test@kakao.com");
        when(kakaoOAuthClient.getUserInfo("auth-code")).thenReturn(kakaoUserInfo);
        when(userRepository.findByProviderAndProviderId("kakao", "123456789")).thenReturn(Optional.of(testUser));
        mockTokenIssuance();
        mockOnboarding(false, false);

        authService.socialLogin("kakao", "auth-code", response);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("unsupported provider throws")
    void unsupportedProviderThrows() {
        assertThatThrownBy(() -> authService.socialLogin("google", "auth-code", response))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.UNSUPPORTED_PROVIDER));
    }

    @Test
    @DisplayName("social login includes onboarding status")
    void socialLoginIncludesOnboardingStatus() {
        KakaoUserInfo kakaoUserInfo = new KakaoUserInfo("123456789", "modu", "test@kakao.com");
        when(kakaoOAuthClient.getUserInfo("auth-code")).thenReturn(kakaoUserInfo);
        when(userRepository.findByProviderAndProviderId("kakao", "123456789")).thenReturn(Optional.of(testUser));
        mockTokenIssuance();
        mockOnboarding(true, false);

        LoginResponse result = authService.socialLogin("kakao", "auth-code", response);

        assertThat(result.onboarding().isSurveyCompleted()).isTrue();
        assertThat(result.onboarding().isRuleSetCompleted()).isFalse();
    }

    @Test
    @DisplayName("refresh rotates refresh token and returns new access token")
    void refreshRotatesRefreshTokenAndReturnsNewAccessToken() {
        Cookie cookie = new Cookie("refreshToken", "raw-refresh-token");
        when(request.getCookies()).thenReturn(new Cookie[]{cookie});
        when(jwtProvider.hashToken("raw-refresh-token")).thenReturn("hashed-token");

        RefreshToken refreshToken = RefreshToken.builder()
                .userId(1L)
                .tokenHash("hashed-token")
                .issuedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(14))
                .build();
        when(refreshTokenRepository.findByTokenHash("hashed-token")).thenReturn(Optional.of(refreshToken));
        when(jwtProvider.generateAccessToken(1L)).thenReturn("new-access-token");
        when(jwtProvider.generateRefreshToken()).thenReturn("new-refresh-token");
        when(jwtProvider.hashToken("new-refresh-token")).thenReturn("new-hashed-token");
        when(jwtProperties.getRefreshTokenExpiration()).thenReturn(1209600000L);
        when(jwtProvider.createRefreshTokenCookie("new-refresh-token"))
                .thenReturn(ResponseCookie.from("refreshToken", "new-refresh-token").build());

        TokenResponse result = authService.refresh(request, response);

        assertThat(result.accessToken()).isEqualTo("new-access-token");
        verify(refreshTokenRepository).delete(refreshToken);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
        verify(response).addHeader(eqSetCookie(), anyString());
    }

    @Test
    @DisplayName("refresh without cookie throws")
    void refreshWithoutCookieThrows() {
        when(request.getCookies()).thenReturn(null);

        assertThatThrownBy(() -> authService.refresh(request, response))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }

    @Test
    @DisplayName("refresh with revoked refresh token throws")
    void refreshWithRevokedRefreshTokenThrows() {
        Cookie cookie = new Cookie("refreshToken", "raw-refresh-token");
        when(request.getCookies()).thenReturn(new Cookie[]{cookie});
        when(jwtProvider.hashToken("raw-refresh-token")).thenReturn("hashed-token");

        RefreshToken revokedToken = RefreshToken.builder()
                .userId(1L)
                .tokenHash("hashed-token")
                .issuedAt(OffsetDateTime.now().minusDays(1))
                .expiresAt(OffsetDateTime.now().plusDays(13))
                .build();
        revokedToken.revoke();
        when(refreshTokenRepository.findByTokenHash("hashed-token")).thenReturn(Optional.of(revokedToken));

        assertThatThrownBy(() -> authService.refresh(request, response))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }

    @Test
    @DisplayName("refresh with expired refresh token throws")
    void refreshWithExpiredRefreshTokenThrows() {
        Cookie cookie = new Cookie("refreshToken", "raw-refresh-token");
        when(request.getCookies()).thenReturn(new Cookie[]{cookie});
        when(jwtProvider.hashToken("raw-refresh-token")).thenReturn("hashed-token");

        RefreshToken expiredToken = RefreshToken.builder()
                .userId(1L)
                .tokenHash("hashed-token")
                .issuedAt(OffsetDateTime.now().minusDays(15))
                .expiresAt(OffsetDateTime.now().minusDays(1))
                .build();
        when(refreshTokenRepository.findByTokenHash("hashed-token")).thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> authService.refresh(request, response))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }

    @Test
    @DisplayName("logout revokes refresh token blacklists access token and expires refresh cookie")
    void logoutRevokesRefreshTokenBlacklistsAccessTokenAndExpiresRefreshCookie() {
        Cookie cookie = new Cookie("refreshToken", "raw-refresh-token");
        when(request.getCookies()).thenReturn(new Cookie[]{cookie});
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer raw-access-token");
        when(jwtProvider.hashToken("raw-refresh-token")).thenReturn("hashed-token");
        when(jwtProvider.getRemainingExpirationMillis("raw-access-token")).thenReturn(1000L);
        when(jwtProvider.expireRefreshTokenCookie())
                .thenReturn(ResponseCookie.from("refreshToken", "").maxAge(0).build());

        RefreshToken refreshToken = RefreshToken.builder()
                .userId(1L)
                .tokenHash("hashed-token")
                .issuedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(14))
                .build();
        when(refreshTokenRepository.findByTokenHash("hashed-token")).thenReturn(Optional.of(refreshToken));

        authService.logout(request, response);

        assertThat(refreshToken.isRevoked()).isTrue();
        verify(jwtProvider).validateToken("raw-access-token");
        verify(accessTokenBlacklistService).blacklist("raw-access-token", 1000L);
        verify(response).addHeader(eqSetCookie(), anyString());
    }

    @Test
    @DisplayName("logout without cookies still expires refresh cookie")
    void logoutWithoutCookiesStillExpiresRefreshCookie() {
        when(request.getCookies()).thenReturn(null);
        when(jwtProvider.expireRefreshTokenCookie())
                .thenReturn(ResponseCookie.from("refreshToken", "").maxAge(0).build());

        authService.logout(request, response);

        verify(accessTokenBlacklistService, never()).blacklist(anyString(), anyLong());
        verify(response).addHeader(eqSetCookie(), anyString());
    }

    @Test
    @DisplayName("logout with expired access token still expires refresh cookie")
    void logoutWithExpiredAccessTokenStillExpiresRefreshCookie() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer expired-access-token");
        doThrow(new ApiException(AuthErrorCode.EXPIRED_TOKEN))
                .when(jwtProvider).validateToken("expired-access-token");
        when(request.getCookies()).thenReturn(null);
        when(jwtProvider.expireRefreshTokenCookie())
                .thenReturn(ResponseCookie.from("refreshToken", "").maxAge(0).build());

        authService.logout(request, response);

        verify(accessTokenBlacklistService, never()).blacklist(anyString(), anyLong());
        verify(response).addHeader(eqSetCookie(), anyString());
    }

    @Test
    @DisplayName("test login returns access token in body")
    void testLoginReturnsAccessTokenInBody() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        mockTokenIssuance();
        mockOnboarding(false, false);

        LoginResponse result = authService.testLogin(1L, response);

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.userId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("test login with missing user throws")
    void testLoginWithMissingUserThrows() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.testLogin(999L, response))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.USER_NOT_FOUND));
    }

    private String eqSetCookie() {
        return org.mockito.ArgumentMatchers.eq(HttpHeaders.SET_COOKIE);
    }
}
