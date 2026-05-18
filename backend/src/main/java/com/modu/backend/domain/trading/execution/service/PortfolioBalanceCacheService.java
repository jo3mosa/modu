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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    /**
     * 사용자별 single-flight 락 — 단일 인스턴스 가정. 다중 인스턴스는 분산 락 followups.
     * 동일 userId 동시 cache miss → 한 호출만 KIS 진입, 나머지는 결과 대기 후 캐시 hit.
     */
    private final ConcurrentMap<Long, Object> singleFlight = new ConcurrentHashMap<>();

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
        Optional<PortfolioResponse> cached = readCache(key, userId);
        if (cached.isPresent()) return cached;

        // single-flight — 동일 userId 동시 miss 시 한 호출만 KIS 진입
        Object lock = singleFlight.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            try {
                // double-check — 락 대기 동안 다른 스레드가 채웠을 수 있음
                Optional<PortfolioResponse> again = readCache(key, userId);
                if (again.isPresent()) return again;

                Optional<PortfolioResponse> fresh = callKis(userId);
                fresh.ifPresent(value -> writeCache(key, value));
                return fresh;
            } finally {
                singleFlight.remove(userId, lock);
            }
        }
    }

    private Optional<PortfolioResponse> readCache(String key, Long userId) {
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Optional.of(objectMapper.readValue(cached, PortfolioResponse.class));
            }
        } catch (Exception e) {
            log.warn("[PortfolioBalanceCache] 캐시 read 실패 — KIS 호출로 폴백. userId: {}", userId, e);
        }
        return Optional.empty();
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
            // KIS_NOT_CONNECTED 는 도메인 설정 오류 — Optional.empty 로 삼키면 caller 가 일시 실패로 오인.
            // 재전파해 상위 (snapshot listener / balance check) 가 명확히 알 수 있게 한다.
            if (e.getErrorCode() == UserErrorCode.KIS_NOT_CONNECTED) {
                log.warn("[PortfolioBalanceCache] KIS_NOT_CONNECTED — 도메인 오류 재전파. userId: {}", userId);
                throw e;
            }
            log.warn("[PortfolioBalanceCache] KIS 호출 일시 실패 - userId: {}, code: {}",
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
