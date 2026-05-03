package com.modu.backend.domain.auth.service;

import com.modu.backend.domain.auth.client.KakaoOAuthClient;
import com.modu.backend.domain.auth.client.KakaoUserInfo;
import com.modu.backend.domain.auth.dto.LoginResponse;
import com.modu.backend.domain.auth.dto.OnboardingStatus;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;

/**
 * 인증 도메인 서비스
 *
 * 소셜 로그인, 토큰 재발급, 로그아웃, 개발용 테스트 로그인 처리
 * 토큰 발급 및 쿠키 세팅은 issueTokensAndBuildResponse()로 공통 처리
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final InvestmentProfileRepository investmentProfileRepository;
    private final TradingRuleRepository tradingRuleRepository;

    // ── 소셜 로그인 ────────────────────────────────────────────────────────────

    /**
     * 카카오 인가 코드로 로그인 처리
     *
     * 1. provider 유효성 검사
     * 2. 카카오 사용자 정보 조회
     * 3. 유저 조회 또는 신규 생성
     * 4. 기존 Refresh Token 삭제 후 재발급
     * 5. 온보딩 여부 조회 후 응답 반환
     */
    public LoginResponse socialLogin(String provider, String code, HttpServletResponse response) {
        if (!"kakao".equals(provider)) {
            throw new ApiException(AuthErrorCode.UNSUPPORTED_PROVIDER);
        }

        KakaoUserInfo kakaoUserInfo = kakaoOAuthClient.getUserInfo(code);

        User user = userRepository.findByProviderAndProviderId("kakao", kakaoUserInfo.providerId())
                .orElseGet(() -> userRepository.save(User.builder()
                        .provider("kakao")
                        .providerId(kakaoUserInfo.providerId())
                        .nickname(kakaoUserInfo.nickname())
                        .email(kakaoUserInfo.email())
                        .build()));

        refreshTokenRepository.deleteByUserId(user.getId());

        return issueTokensAndBuildResponse(user, response);
    }

    // ── 토큰 재발급 ────────────────────────────────────────────────────────────

    /**
     * Refresh Token으로 새 Access Token 발급
     *
     * 쿠키의 Refresh Token 원문 → 해시값으로 DB 조회 → 유효성 확인 → Access Token 재발급
     */
    public void refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = extractRefreshTokenFromCookie(request);
        String tokenHash = jwtProvider.hashToken(rawToken);

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ApiException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));

        if (refreshToken.isRevoked() || refreshToken.isExpired()) {
            throw new ApiException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }

        String newAccessToken = jwtProvider.generateAccessToken(refreshToken.getUserId());
        response.addHeader("Set-Cookie", jwtProvider.createAccessTokenCookie(newAccessToken).toString());
    }

    // ── 로그아웃 ───────────────────────────────────────────────────────────────

    /**
     * 로그아웃 처리
     *
     * Refresh Token revoke 후 Access/Refresh Token 쿠키 만료 처리
     */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        try {
            String rawToken = extractRefreshTokenFromCookie(request);
            String tokenHash = jwtProvider.hashToken(rawToken);
            refreshTokenRepository.findByTokenHash(tokenHash)
                    .ifPresent(RefreshToken::revoke);
        } catch (ApiException ignored) {
            // 쿠키 없어도 로그아웃은 성공 처리
        }

        response.addHeader("Set-Cookie", jwtProvider.expireAccessTokenCookie().toString());
        response.addHeader("Set-Cookie", jwtProvider.expireRefreshTokenCookie().toString());
    }

    // ── 개발용 테스트 로그인 ────────────────────────────────────────────────────

    /**
     * userId로 바로 로그인 처리 (개발 환경 전용)
     *
     * 실제 소셜 로그인 없이 userId만으로 JWT 발급
     */
    public LoginResponse testLogin(Long userId, HttpServletResponse response) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(AuthErrorCode.USER_NOT_FOUND));

        refreshTokenRepository.deleteByUserId(user.getId());

        return issueTokensAndBuildResponse(user, response);
    }

    // ── 공통 처리 ──────────────────────────────────────────────────────────────

    /** Access/Refresh Token 발급, 쿠키 세팅, 온보딩 여부 조회 후 LoginResponse 반환 */
    private LoginResponse issueTokensAndBuildResponse(User user, HttpServletResponse response) {
        String accessToken = jwtProvider.generateAccessToken(user.getId());
        String refreshToken = jwtProvider.generateRefreshToken();

        OffsetDateTime now = OffsetDateTime.now();
        long refreshTokenExpirationSeconds = jwtProperties.getRefreshTokenExpiration() / 1000;

        refreshTokenRepository.save(RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(jwtProvider.hashToken(refreshToken))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(refreshTokenExpirationSeconds))
                .build());

        response.addHeader("Set-Cookie", jwtProvider.createAccessTokenCookie(accessToken).toString());
        response.addHeader("Set-Cookie", jwtProvider.createRefreshTokenCookie(refreshToken).toString());

        boolean isSurveyCompleted = investmentProfileRepository.existsByUserId(user.getId());
        boolean isRuleSetCompleted = tradingRuleRepository.existsByUserId(user.getId());

        return new LoginResponse(
                user.getId(),
                user.getNickname(),
                user.getEmail(),
                new OnboardingStatus(isSurveyCompleted, isRuleSetCompleted)
        );
    }

    /** 쿠키에서 Refresh Token 원문 추출 */
    private String extractRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            throw new ApiException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> "refreshToken".equals(c.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElseThrow(() -> new ApiException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }
}
