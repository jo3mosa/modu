package com.modu.backend.domain.auth.service;

import com.modu.backend.domain.auth.client.KakaoOAuthClient;
import com.modu.backend.domain.auth.client.KakaoUserInfo;
import com.modu.backend.domain.auth.dto.LoginResponse;
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
import org.springframework.http.ResponseCookie;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
                .nickname("테스트유저")
                .email("test@kakao.com")
                .build();
        ReflectionTestUtils.setField(testUser, "id", 1L);
    }

    // 토큰 발급 공통 Mock 세팅
    private void mockTokenIssuance() {
        when(jwtProvider.generateAccessToken(anyLong())).thenReturn("access-token");
        when(jwtProvider.generateRefreshToken()).thenReturn("refresh-token");
        when(jwtProvider.hashToken("refresh-token")).thenReturn("hashed-refresh-token");
        when(jwtProperties.getRefreshTokenExpiration()).thenReturn(1209600000L);
        when(jwtProvider.createAccessTokenCookie("access-token"))
                .thenReturn(ResponseCookie.from("accessToken", "access-token").build());
        when(jwtProvider.createRefreshTokenCookie("refresh-token"))
                .thenReturn(ResponseCookie.from("refreshToken", "refresh-token").build());
        when(investmentProfileRepository.existsByUserId(1L)).thenReturn(false);
        when(tradingRuleRepository.existsByUserId(1L)).thenReturn(false);
    }

    // ── 소셜 로그인 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("신규 유저 소셜 로그인 시 User 저장 후 토큰 발급")
    void 신규_유저_소셜_로그인_시_User_저장_후_토큰_발급() {
        // given
        KakaoUserInfo kakaoUserInfo = new KakaoUserInfo("123456789", "테스트유저", "test@kakao.com");
        when(kakaoOAuthClient.getUserInfo("auth-code")).thenReturn(kakaoUserInfo);
        when(userRepository.findByProviderAndProviderId("kakao", "123456789")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        mockTokenIssuance();

        // when
        LoginResponse result = authService.socialLogin("kakao", "auth-code", response);

        // then
        verify(userRepository).save(any(User.class));  // 신규 유저 저장 호출 확인
        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.nickname()).isEqualTo("테스트유저");
    }

    @Test
    @DisplayName("기존 유저 소셜 로그인 시 User 저장 미호출")
    void 기존_유저_소셜_로그인_시_User_저장_미호출() {
        // given
        KakaoUserInfo kakaoUserInfo = new KakaoUserInfo("123456789", "테스트유저", "test@kakao.com");
        when(kakaoOAuthClient.getUserInfo("auth-code")).thenReturn(kakaoUserInfo);
        when(userRepository.findByProviderAndProviderId("kakao", "123456789")).thenReturn(Optional.of(testUser));
        mockTokenIssuance();

        // when
        authService.socialLogin("kakao", "auth-code", response);

        // then
        verify(userRepository, never()).save(any(User.class));  // 기존 유저는 저장 미호출
    }

    @Test
    @DisplayName("지원하지 않는 provider 입력 시 UNSUPPORTED_PROVIDER 예외")
    void 지원하지_않는_provider_입력_시_UNSUPPORTED_PROVIDER_예외() {
        assertThatThrownBy(() -> authService.socialLogin("google", "auth-code", response))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.UNSUPPORTED_PROVIDER));
    }

    @Test
    @DisplayName("소셜 로그인 성공 시 onboarding 상태 응답에 포함")
    void 소셜_로그인_성공_시_onboarding_상태_응답에_포함() {
        // given
        KakaoUserInfo kakaoUserInfo = new KakaoUserInfo("123456789", "테스트유저", "test@kakao.com");
        when(kakaoOAuthClient.getUserInfo("auth-code")).thenReturn(kakaoUserInfo);
        when(userRepository.findByProviderAndProviderId("kakao", "123456789")).thenReturn(Optional.of(testUser));
        mockTokenIssuance();
        when(investmentProfileRepository.existsByUserId(1L)).thenReturn(true);
        when(tradingRuleRepository.existsByUserId(1L)).thenReturn(false);

        // when
        LoginResponse result = authService.socialLogin("kakao", "auth-code", response);

        // then
        assertThat(result.onboarding().isSurveyCompleted()).isTrue();
        assertThat(result.onboarding().isRuleSetCompleted()).isFalse();
    }

    // ── 토큰 재발급 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 Refresh Token으로 Access Token 재발급 성공")
    void 유효한_Refresh_Token으로_Access_Token_재발급_성공() {
        // given
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
        when(jwtProvider.createAccessTokenCookie("new-access-token"))
                .thenReturn(ResponseCookie.from("accessToken", "new-access-token").build());

        // when
        authService.refresh(request, response);

        // then
        verify(response).addHeader(anyString(), anyString());
    }

    @Test
    @DisplayName("쿠키에 Refresh Token 없을 때 REFRESH_TOKEN_NOT_FOUND 예외")
    void 쿠키에_Refresh_Token_없을_때_예외() {
        // given
        when(request.getCookies()).thenReturn(null);

        // when & then
        assertThatThrownBy(() -> authService.refresh(request, response))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }

    @Test
    @DisplayName("revoke된 Refresh Token으로 재발급 시 REFRESH_TOKEN_NOT_FOUND 예외")
    void revoke된_Refresh_Token으로_재발급_시_예외() {
        // given
        Cookie cookie = new Cookie("refreshToken", "raw-refresh-token");
        when(request.getCookies()).thenReturn(new Cookie[]{cookie});
        when(jwtProvider.hashToken("raw-refresh-token")).thenReturn("hashed-token");

        RefreshToken revokedToken = RefreshToken.builder()
                .userId(1L)
                .tokenHash("hashed-token")
                .issuedAt(OffsetDateTime.now().minusDays(1))
                .expiresAt(OffsetDateTime.now().plusDays(13))
                .build();
        revokedToken.revoke();  // 무효화
        when(refreshTokenRepository.findByTokenHash("hashed-token")).thenReturn(Optional.of(revokedToken));

        // when & then
        assertThatThrownBy(() -> authService.refresh(request, response))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }

    @Test
    @DisplayName("만료된 Refresh Token으로 재발급 시 REFRESH_TOKEN_NOT_FOUND 예외")
    void 만료된_Refresh_Token으로_재발급_시_예외() {
        // given
        Cookie cookie = new Cookie("refreshToken", "raw-refresh-token");
        when(request.getCookies()).thenReturn(new Cookie[]{cookie});
        when(jwtProvider.hashToken("raw-refresh-token")).thenReturn("hashed-token");

        RefreshToken expiredToken = RefreshToken.builder()
                .userId(1L)
                .tokenHash("hashed-token")
                .issuedAt(OffsetDateTime.now().minusDays(15))
                .expiresAt(OffsetDateTime.now().minusDays(1))  // 이미 만료
                .build();
        when(refreshTokenRepository.findByTokenHash("hashed-token")).thenReturn(Optional.of(expiredToken));

        // when & then
        assertThatThrownBy(() -> authService.refresh(request, response))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }

    // ── 로그아웃 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 로그아웃 시 Refresh Token revoke 및 쿠키 만료 처리")
    void 정상_로그아웃_시_revoke_및_쿠키_만료() {
        // given
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
        when(jwtProvider.expireAccessTokenCookie())
                .thenReturn(ResponseCookie.from("accessToken", "").maxAge(0).build());
        when(jwtProvider.expireRefreshTokenCookie())
                .thenReturn(ResponseCookie.from("refreshToken", "").maxAge(0).build());

        // when
        authService.logout(request, response);

        // then
        assertThat(refreshToken.isRevoked()).isTrue();  // revoke 호출 확인
        verify(response, org.mockito.Mockito.times(2)).addHeader(anyString(), anyString());
    }

    @Test
    @DisplayName("쿠키 없어도 로그아웃 성공 처리")
    void 쿠키_없어도_로그아웃_성공() {
        // given
        when(request.getCookies()).thenReturn(null);
        when(jwtProvider.expireAccessTokenCookie())
                .thenReturn(ResponseCookie.from("accessToken", "").maxAge(0).build());
        when(jwtProvider.expireRefreshTokenCookie())
                .thenReturn(ResponseCookie.from("refreshToken", "").maxAge(0).build());

        // when & then - 예외 없이 정상 처리
        authService.logout(request, response);
        verify(response, org.mockito.Mockito.times(2)).addHeader(anyString(), anyString());
    }

    // ── 테스트 로그인 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("테스트 로그인 성공 시 LoginResponse 반환")
    void 테스트_로그인_성공() {
        // given
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        mockTokenIssuance();

        // when
        LoginResponse result = authService.testLogin(1L, response);

        // then
        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.nickname()).isEqualTo("테스트유저");
    }

    @Test
    @DisplayName("존재하지 않는 userId로 테스트 로그인 시 USER_NOT_FOUND 예외")
    void 존재하지_않는_userId_테스트_로그인_시_예외() {
        // given
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> authService.testLogin(999L, response))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.USER_NOT_FOUND));
    }
}
