package com.modu.backend.domain.trading.execution.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modu.backend.domain.account.client.KisBalanceClient;
import com.modu.backend.domain.account.dto.PortfolioResponse;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import com.modu.backend.global.kis.KisApiCallTemplate;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 사용자 포트폴리오 잔고 단기 캐시 — S14P31B106-291
 *
 * KIS inquire-balance API 의 사용자별 호출 부하 보호용. 다중 체결 시 동시간대 캐시 hit.
 *
 * [캐시 키 / TTL]
 *  Key: portfolio:cache:balance:{userId}
 *  TTL: 10초 (Q2 결정)
 *  Value: PortfolioResponse JSON
 *
 * [AI 측 portfolio:snapshot:{userId} 와 분리]
 *  AI 측이 보는 키 (영구) 와 본 캐시 (10s TTL) 는 별개. 본 캐시는 KIS 호출 보호 내부용.
 *
 * [stale 허용]
 *  10초 이내 동일 사용자 다중 체결 시 첫 체결 직후 KIS 호출 결과를 후속 9초 동안 재사용 →
 *  AI 가 보는 snapshot 도 약간 stale. 외부 거래 (모바일/HTS) 정합성 약간 손실.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioBalanceCacheService {

    private static final String KEY_PREFIX = "portfolio:cache:balance:";
    private static final Duration TTL = Duration.ofSeconds(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KisBalanceClient kisBalanceClient;
    private final KisCredentialRepository kisCredentialRepository;
    private final AesGcmEncryptor encryptor;
    private final KisApiCallTemplate kisApiCallTemplate;

    /**
     * 사용자 포트폴리오 — 캐시 hit 시 즉시 반환, miss 시 KIS 호출 + 캐시 SET.
     *
     * @return KIS 호출 실패 시 Optional.empty (caller 가 fallback 처리)
     */
    public Optional<PortfolioResponse> fetch(Long userId) {
        String key = KEY_PREFIX + userId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Optional.of(objectMapper.readValue(cached, PortfolioResponse.class));
            }
        } catch (Exception e) {
            log.warn("[PortfolioBalanceCache] 캐시 read 실패 — KIS 호출로 폴백. userId: {}", userId, e);
        }

        Optional<PortfolioResponse> fresh = callKis(userId);
        fresh.ifPresent(value -> writeCache(key, value));
        return fresh;
    }

    private Optional<PortfolioResponse> callKis(Long userId) {
        try {
            KisCredential credential = kisCredentialRepository.findByUserId(userId)
                    .orElseThrow(() -> new ApiException(UserErrorCode.KIS_NOT_CONNECTED));
            String appKey    = encryptor.decrypt(credential.getAppKeyEnc());
            String appSecret = encryptor.decrypt(credential.getAppSecretEnc());

            PortfolioResponse response = kisApiCallTemplate.callWithTokenRetry(userId, appKey, appSecret,
                    token -> kisBalanceClient.getPortfolio(
                            token, appKey, appSecret,
                            credential.getAccountNo(), credential.getAccountPrdtCd()));
            return Optional.of(response);
        } catch (ApiException e) {
            log.warn("[PortfolioBalanceCache] KIS 호출 실패 - userId: {}, code: {}",
                    userId, e.getErrorCode().getCode());
            return Optional.empty();
        } catch (Exception e) {
            log.error("[PortfolioBalanceCache] KIS 호출 미처리 예외 - userId: {}", userId, e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        }
    }

    private void writeCache(String key, PortfolioResponse value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, TTL);
        } catch (JsonProcessingException e) {
            log.warn("[PortfolioBalanceCache] JSON 직렬화 실패 — 캐시 skip. key: {}", key, e);
        } catch (Exception e) {
            log.warn("[PortfolioBalanceCache] Redis SET 실패 — 캐시 skip. key: {}", key, e);
        }
    }
}
