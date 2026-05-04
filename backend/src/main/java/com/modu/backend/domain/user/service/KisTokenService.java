package com.modu.backend.domain.user.service;

import com.modu.backend.domain.user.client.KisTokenClient;
import com.modu.backend.domain.user.entity.KisToken;
import com.modu.backend.domain.user.repository.KisTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * KIS 토큰 관리 서비스
 *
 * [토큰 종류]
 * - ACCESS_TOKEN  : KIS REST API 호출용 Bearer 토큰 (유효 24시간)
 * - WEBSOCKET_KEY : KIS 실시간 웹소켓 접속키 (유효 24시간, 세션 연결 시 1회 사용)
 *
 * [캐싱 전략]
 * - 유효한 토큰이 DB에 있으면 재사용
 * - 없거나 만료된 경우 KIS API로 재발급 후 저장
 */
@Service
@RequiredArgsConstructor
@Transactional
public class KisTokenService {

    private static final String ACCESS_TOKEN = "ACCESS_TOKEN";
    private static final String WEBSOCKET_KEY = "WEBSOCKET_KEY";

    private final KisTokenRepository kisTokenRepository;
    private final KisTokenClient kisTokenClient;

    // ── Access Token ────────────────────────────────────────────────────────────

    /**
     * 유효한 KIS 액세스 토큰 반환 (캐시 우선, 없으면 신규 발급)
     */
    public String getOrIssueAccessToken(Long userId, String decryptedAppKey, String decryptedAppSecret) {
        return kisTokenRepository.findValidToken(userId, ACCESS_TOKEN, OffsetDateTime.now())
                .map(KisToken::getAccessToken)
                .orElseGet(() -> issueAndSaveAccessToken(userId, decryptedAppKey, decryptedAppSecret));
    }

    /**
     * KIS API로 액세스 토큰 신규 발급 후 DB 저장
     *
     * KIS 연동 등록 시 자격증명 유효성 검증 목적으로도 사용
     */
    public String issueAndSaveAccessToken(Long userId, String decryptedAppKey, String decryptedAppSecret) {
        String accessToken = kisTokenClient.issueAccessToken(decryptedAppKey, decryptedAppSecret);
        saveToken(userId, ACCESS_TOKEN, accessToken);
        return accessToken;
    }

    // ── WebSocket Key ───────────────────────────────────────────────────────────

    /**
     * 유효한 KIS 웹소켓 접속키 반환 (캐시 우선, 없으면 신규 발급)
     */
    public String getOrIssueWebSocketKey(Long userId, String decryptedAppKey, String decryptedAppSecret) {
        return kisTokenRepository.findValidToken(userId, WEBSOCKET_KEY, OffsetDateTime.now())
                .map(KisToken::getAccessToken)
                .orElseGet(() -> issueAndSaveWebSocketKey(userId, decryptedAppKey, decryptedAppSecret));
    }

    /**
     * KIS API로 웹소켓 접속키 신규 발급 후 DB 저장
     *
     * KIS 연동 등록 시 액세스 토큰과 함께 즉시 발급
     */
    public String issueAndSaveWebSocketKey(Long userId, String decryptedAppKey, String decryptedAppSecret) {
        String webSocketKey = kisTokenClient.issueWebSocketKey(decryptedAppKey, decryptedAppSecret);
        saveToken(userId, WEBSOCKET_KEY, webSocketKey);
        return webSocketKey;
    }

    // ── 내부 유틸 ──────────────────────────────────────────────────────────────

    private void saveToken(Long userId, String tokenType, String tokenValue) {
        OffsetDateTime now = OffsetDateTime.now();
        kisTokenRepository.save(KisToken.builder()
                .userId(userId)
                .tokenType(tokenType)
                .accessToken(tokenValue)
                .issuedAt(now)
                .expiresAt(now.plusDays(1))
                .build());
    }
}
