package com.modu.backend.domain.market.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.market.cache.RealtimePriceCacheService;
import com.modu.backend.domain.market.websocket.KisRealtimeMessageParser;
import com.modu.backend.domain.trading.execution.parser.ExecutionMessagePayloadParser;
import com.modu.backend.domain.trading.execution.service.ExecutionDispatchService;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.config.KisProfiles;
import com.modu.backend.global.config.KisWebSocketProperties;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * gateway 전용 — {@link UserKisSession} 인스턴스 생성 책임 분리.
 *
 * 사용자 자격증명 조회 + AES 복호화 + approval_key 발급을 한 곳에 모은다.
 * UserKisSessionPool 이 이 factory 를 사용해 사용자별 세션을 lazy 또는 부팅 시 일괄 생성.
 */
@Slf4j
@Component
@Profile(KisProfiles.GATEWAY)
@RequiredArgsConstructor
public class UserKisSessionFactory {

    private final KisCredentialRepository credentialRepository;
    private final KisTokenService kisTokenService;
    private final AesGcmEncryptor encryptor;
    private final KisWebSocketProperties properties;
    private final ObjectMapper objectMapper;
    private final KisRealtimeMessageParser priceParser;
    private final ExecutionMessagePayloadParser executionParser;
    private final ExecutionDispatchService executionDispatch;
    private final KisFeedPublisher feedPublisher;
    private final RealtimePriceCacheService priceCache;

    /**
     * 사용자 자격증명 → 복호화 → approval_key 발급 → UserKisSession 인스턴스 생성.
     *
     * @param onInvalidApproval KIS 가 승인키를 "invalid approval" 로 거부했을 때 세션이 호출하는 복구 콜백.
     *                          (Pool 이 승인키 evict + 세션 재생성을 수행하도록 위임)
     * @throws IllegalStateException 자격증명이 없거나 잘못된 경우
     */
    public UserKisSession create(long userId, Runnable onInvalidApproval) {
        KisCredential cred = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "KIS credential not found - userId: " + userId));

        String appKey = encryptor.decrypt(cred.getAppKeyEnc());
        String appSecret = encryptor.decrypt(cred.getAppSecretEnc());
        String htsId = cred.getHtsIdEnc() != null ? encryptor.decrypt(cred.getHtsIdEnc()) : null;
        String approvalKey = kisTokenService.getOrIssueWebSocketKey(userId, appKey, appSecret);

        return new UserKisSession(
                userId,
                htsId,
                approvalKey,
                properties,
                objectMapper,
                priceParser,
                executionParser,
                executionDispatch,
                feedPublisher,
                priceCache,
                onInvalidApproval
        );
    }
}
