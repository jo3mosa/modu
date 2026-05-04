package com.modu.backend.domain.user.service;

import com.modu.backend.domain.user.client.KisTokenClient;
import com.modu.backend.domain.user.entity.KisToken;
import com.modu.backend.domain.user.repository.KisTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * KIS 액세스 토큰 관리 서비스
 *
 * [토큰 캐싱 전략]
 * - 유효한 토큰이 DB에 있으면 재사용
 * - 없거나 만료된 경우 KIS API로 재발급 후 저장
 * - 유효기간: 1일 (KIS 정책)
 */
@Service
@RequiredArgsConstructor
@Transactional
public class KisTokenService {

    private final KisTokenRepository kisTokenRepository;
    private final KisTokenClient kisTokenClient;

    /**
     * 유효한 KIS 액세스 토큰 반환 (캐시 우선, 없으면 신규 발급)
     *
     * @param userId            사용자 ID
     * @param decryptedAppKey   복호화된 App Key
     * @param decryptedAppSecret 복호화된 App Secret
     * @return KIS Bearer 액세스 토큰
     */
    public String getOrIssueToken(Long userId, String decryptedAppKey, String decryptedAppSecret) {
        return kisTokenRepository.findValidToken(userId, OffsetDateTime.now())
                .map(KisToken::getAccessToken)
                .orElseGet(() -> issueAndSaveToken(userId, decryptedAppKey, decryptedAppSecret));
    }

    /**
     * KIS API로 신규 토큰 발급 후 DB 저장
     *
     * KIS 연동 등록 시 자격증명 유효성 검증 목적으로도 사용
     *
     * @param userId            사용자 ID
     * @param decryptedAppKey   복호화된 App Key
     * @param decryptedAppSecret 복호화된 App Secret
     * @return 발급된 KIS Bearer 액세스 토큰
     */
    public String issueAndSaveToken(Long userId, String decryptedAppKey, String decryptedAppSecret) {
        String accessToken = kisTokenClient.issueAccessToken(decryptedAppKey, decryptedAppSecret);

        OffsetDateTime now = OffsetDateTime.now();
        kisTokenRepository.save(KisToken.builder()
                .userId(userId)
                .tokenType("ACCESS_TOKEN")
                .accessToken(accessToken)
                .issuedAt(now)
                .expiresAt(now.plusDays(1))
                .build());

        return accessToken;
    }
}
