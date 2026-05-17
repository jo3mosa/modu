package com.modu.backend.domain.auth.service;

import com.modu.backend.domain.auth.client.KakaoOAuthClient;
import com.modu.backend.domain.auth.client.KakaoUserInfo;
import com.modu.backend.domain.auth.dto.LoginResponse;
import com.modu.backend.domain.auth.dto.OnboardingStatus;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private static final String BEARER_PREFIX = "Bearer ";

    private final KakaoOAuthClient kakaoOAuthClient;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final InvestmentProfileRepository investmentProfileRepository;
    private final TradingRuleRepository tradingRuleRepository;
    private final AccessTokenBlacklistService accessTokenBlacklistService;

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

    public TokenResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = extractRefreshTokenFromCookie(request);
        String tokenHash = jwtProvider.hashToken(rawToken);

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ApiException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND));

        if (refreshToken.isRevoked() || refreshToken.isExpired()) {
            throw new ApiException(AuthErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }

        Long userId = refreshToken.getUserId();
        refreshTokenRepository.deleteByUserId(userId);

        String newAccessToken = jwtProvider.generateAccessToken(userId);
        String newRefreshToken = jwtProvider.generateRefreshToken();
        saveRefreshToken(userId, newRefreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE, jwtProvider.createRefreshTokenCookie(newRefreshToken).toString());
        return new TokenResponse(newAccessToken);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        blacklistAccessTokenIfPresent(request);
        revokeRefreshTokenIfPresent(request);
        response.addHeader(HttpHeaders.SET_COOKIE, jwtProvider.expireRefreshTokenCookie().toString());
    }

    public LoginResponse testLogin(Long userId, HttpServletResponse response) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(AuthErrorCode.USER_NOT_FOUND));

        refreshTokenRepository.deleteByUserId(user.getId());

        return issueTokensAndBuildResponse(user, response);
    }

    private LoginResponse issueTokensAndBuildResponse(User user, HttpServletResponse response) {
        String accessToken = jwtProvider.generateAccessToken(user.getId());
        String refreshToken = jwtProvider.generateRefreshToken();

        saveRefreshToken(user.getId(), refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, jwtProvider.createRefreshTokenCookie(refreshToken).toString());

        boolean isSurveyCompleted = investmentProfileRepository.existsByUserId(user.getId());
        boolean isRuleSetCompleted = tradingRuleRepository.existsByUserId(user.getId());

        return new LoginResponse(
                accessToken,
                user.getId(),
                user.getNickname(),
                user.getEmail(),
                new OnboardingStatus(isSurveyCompleted, isRuleSetCompleted)
        );
    }

    private void saveRefreshToken(Long userId, String refreshToken) {
        OffsetDateTime now = OffsetDateTime.now();
        long refreshTokenExpirationSeconds = jwtProperties.getRefreshTokenExpiration() / 1000;

        refreshTokenRepository.save(RefreshToken.builder()
                .userId(userId)
                .tokenHash(jwtProvider.hashToken(refreshToken))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(refreshTokenExpirationSeconds))
                .build());
    }

    private void revokeRefreshTokenIfPresent(HttpServletRequest request) {
        try {
            String rawToken = extractRefreshTokenFromCookie(request);
            String tokenHash = jwtProvider.hashToken(rawToken);
            refreshTokenRepository.findByTokenHash(tokenHash)
                    .ifPresent(RefreshToken::revoke);
        } catch (ApiException ignored) {
            // Logout should still clear the client refresh cookie even when it is absent or already invalid.
        }
    }

    private void blacklistAccessTokenIfPresent(HttpServletRequest request) {
        try {
            Optional<String> accessToken = extractAccessTokenFromAuthorizationHeader(request);
            accessToken.ifPresent(token -> {
                jwtProvider.validateToken(token);
                long ttlMillis = jwtProvider.getRemainingExpirationMillis(token);
                accessTokenBlacklistService.blacklist(token, ttlMillis);
            });
        } catch (ApiException ignored) {
            // Expired or invalid access tokens do not need blacklist registration during logout.
        }
    }

    private Optional<String> extractAccessTokenFromAuthorizationHeader(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return Optional.empty();
        }
        if (!authorization.startsWith(BEARER_PREFIX)) {
            throw new ApiException(AuthErrorCode.INVALID_TOKEN);
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            throw new ApiException(AuthErrorCode.INVALID_TOKEN);
        }
        return Optional.of(token);
    }

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
