package com.modu.backend.domain.trading.execution.service;

import com.modu.backend.domain.account.client.KisBalanceClient;
import com.modu.backend.domain.account.dto.HoldingResponse;
import com.modu.backend.domain.account.dto.PortfolioResponse;
import com.modu.backend.domain.trading.execution.repository.PortfolioSnapshotRedisRepository;
import com.modu.backend.domain.trading.execution.repository.PositionDriftCacheRepository;
import com.modu.backend.domain.trading.execution.repository.PositionDriftDirection;
import com.modu.backend.domain.trading.execution.repository.PositionIndexRedisRepository;
import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.global.kis.KisApiCallTemplate;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 268 단계 6 — KIS 잔고 기반 비동기 검증 + portfolio:snapshot 갱신
 *
 * [트리거]
 *  - 부팅 후 initialDelay (60s) — 부팅 직후 갭 메움
 *  - 이후 fixedDelay (기본 PT1H, application.yml 외부화)
 *
 * [동작]
 *  실계좌 등록 사용자 (KisCredential.isRealAccount=TRUE) 전체 대상:
 *   1. KIS inquire-balance 호출 (callWithTokenRetry — EGW00202 1회 재시도)
 *   2. portfolio:snapshot SET 갱신 (cash/total 은 null — output2 매핑은 followups 2.2)
 *   3. position:index 검증:
 *       - DB 활성 종목 vs KIS 보유 종목 비교 → 둘 모두 union 의 각 종목에 대해 Redis SADD/SREM 정정
 *       - DB-KIS drift 는 ERROR 로그만 (DB 수정은 followups 2.10 책임)
 *
 * [rate limit 보호]
 *  사용자 간 150ms (kis-call-interval-ms) 간격. KIS 실전 1초 20건 한도 대비 안전 마진.
 *
 * [실패 격리]
 *  단일 사용자 실패가 다른 사용자 처리를 막지 않음. ERROR 로그 + 다음 주기 재시도.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionIndexKisVerificationService {

    private final KisCredentialRepository kisCredentialRepository;
    private final PositionThresholdRepository positionThresholdRepository;
    private final PositionIndexRedisRepository positionIndexRedisRepository;
    private final PortfolioSnapshotRedisRepository portfolioSnapshotRedisRepository;
    private final PositionDriftCacheRepository positionDriftCacheRepository;
    private final KisBalanceClient kisBalanceClient;
    private final KisApiCallTemplate kisApiCallTemplate;
    private final AesGcmEncryptor encryptor;

    @Value("${followups.backfill.kis-call-interval-ms:150}")
    private long kisCallIntervalMs;

    /**
     * 초기 60초 후 첫 실행, 이후 1h 주기. (application.yml followups.backfill.kis-verification-interval 로 조정)
     */
    @Async
    @Scheduled(
            initialDelayString = "${followups.backfill.kis-verification-initial-delay:PT60S}",
            fixedDelayString   = "${followups.backfill.kis-verification-interval:PT1H}")
    public void verifyAll() {
        List<KisCredential> credentials = kisCredentialRepository.findAllByIsRealAccountTrue();
        if (credentials.isEmpty()) {
            log.info("[KisVerification] 실계좌 사용자 없음 — skip");
            return;
        }

        long startedAt = System.currentTimeMillis();
        int success = 0;
        int failure = 0;
        for (KisCredential credential : credentials) {
            try {
                verifyUser(credential);
                success++;
            } catch (Exception e) {
                failure++;
                log.error("[KisVerification] 사용자 검증 실패 - userId: {}", credential.getUserId(), e);
            }
            sleepBetweenCalls();
        }
        log.info("[KisVerification] 주기 완료 — 대상: {}, 성공: {}, 실패: {}, 소요: {}ms",
                credentials.size(), success, failure, System.currentTimeMillis() - startedAt);
    }

    private void verifyUser(KisCredential credential) {
        Long userId = credential.getUserId();
        String appKey    = encryptor.decrypt(credential.getAppKeyEnc());
        String appSecret = encryptor.decrypt(credential.getAppSecretEnc());

        PortfolioResponse portfolio = kisApiCallTemplate.callWithTokenRetry(userId, appKey, appSecret,
                token -> kisBalanceClient.getPortfolio(
                        token, appKey, appSecret,
                        credential.getAccountNo(), credential.getAccountPrdtCd()));

        Set<String> kisStocks = collectStockCodes(portfolio);
        refreshPortfolioSnapshot(userId, portfolio);
        reconcilePositionIndex(userId, kisStocks);
    }

    private Set<String> collectStockCodes(PortfolioResponse portfolio) {
        Set<String> stocks = new HashSet<>();
        if (portfolio == null || portfolio.holdings() == null) return stocks;
        for (HoldingResponse h : portfolio.holdings()) {
            if (h.stockCode() != null && h.quantity() != null && h.quantity() > 0) {
                stocks.add(h.stockCode());
            }
        }
        return stocks;
    }

    private void refreshPortfolioSnapshot(Long userId, PortfolioResponse portfolio) {
        List<Map<String, Object>> holdings = new ArrayList<>();
        if (portfolio != null && portfolio.holdings() != null) {
            for (HoldingResponse h : portfolio.holdings()) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("stock_code", h.stockCode());
                entry.put("stock_name", h.stockName());
                entry.put("quantity", h.quantity());
                entry.put("average_price", h.avgBuyPrice());
                holdings.add(entry);
            }
        }
        // cash_balance / total_assets 는 KIS output2 매핑 미구현 → null (followups 2.2)
        portfolioSnapshotRedisRepository.set(userId, null, null, holdings);
    }

    /**
     * Redis position:index 를 KIS 잔고 기준으로 정정 (Redis 만 손댐 — DB drift 는 알림만).
     *
     * S14P31B106-361 (followups 2.10 A-1) — drift 알림 dedup:
     *  - 첫 발견: WARN 로그 + 캐시 mark (25h TTL)
     *  - 재발견 (캐시 hit): DEBUG (운영 로그 노이즈 제거)
     *  - 정상 (양쪽 일치): 이전에 mark 됐던 drift 면 INFO "회복" 로그 + 캐시 clear
     */
    private void reconcilePositionIndex(Long userId, Set<String> kisStocks) {
        Set<String> dbStocks = positionThresholdRepository.findActiveStockCodesByUserId(userId);
        Set<String> union = new HashSet<>();
        union.addAll(kisStocks);
        union.addAll(dbStocks);

        for (String stockCode : union) {
            boolean inKis = kisStocks.contains(stockCode);
            boolean inDb  = dbStocks.contains(stockCode);
            if (inKis) {
                // 멱등 SADD — Redis 휘발 / Listener 실패 갭 메움
                positionIndexRedisRepository.addUser(stockCode, userId);
                if (!inDb) {
                    notifyDrift(userId, stockCode, PositionDriftDirection.KIS_HOLDING_DB_INACTIVE,
                            "KIS 에 보유 / DB 비활성");
                } else {
                    notifyDriftResolvedIfAny(userId, stockCode);
                }
            } else {
                // KIS 에 없음 → Redis 에서 제거 (전량 매도 누락 / HTS 직접 매도 흔적)
                positionIndexRedisRepository.removeUser(stockCode, userId);
                if (inDb) {
                    notifyDrift(userId, stockCode, PositionDriftDirection.KIS_MISSING_DB_ACTIVE,
                            "DB 활성 / KIS 미보유");
                } else {
                    notifyDriftResolvedIfAny(userId, stockCode);
                }
            }
        }
    }

    /**
     * drift 발견 알림 — dedup 캐시 확인 후 첫 발견만 WARN.
     * 재발견은 DEBUG 로 강도 ↓ (외부 거래는 인지된 사용자 행동이라 운영 ERROR 가 아님).
     */
    private void notifyDrift(Long userId, String stockCode, PositionDriftDirection direction, String reason) {
        if (positionDriftCacheRepository.isAlreadyDetected(userId, stockCode, direction)) {
            log.debug("[KisVerification] drift 재발견 (dedup) - userId: {}, stockCode: {}, direction: {}",
                    userId, stockCode, direction);
            return;
        }
        log.warn("[KisVerification] DB drift 첫 발견 - userId: {}, stockCode: {}, 사유: {} (followups 2.10 A-1)",
                userId, stockCode, reason);
        positionDriftCacheRepository.markDetected(userId, stockCode, direction);
    }

    /**
     * 양쪽 일치 시 호출 — 이전에 mark 됐던 drift 가 해소된 경우 INFO 로그.
     * 어느 방향이었는지 모르므로 양쪽 모두 clear 시도 (실제 mark 된 쪽만 true 반환).
     */
    private void notifyDriftResolvedIfAny(Long userId, String stockCode) {
        for (PositionDriftDirection direction : PositionDriftDirection.values()) {
            if (positionDriftCacheRepository.clearDetected(userId, stockCode, direction)) {
                log.info("[KisVerification] drift 해소 - userId: {}, stockCode: {}, direction: {}",
                        userId, stockCode, direction);
            }
        }
    }

    private void sleepBetweenCalls() {
        try {
            Thread.sleep(kisCallIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
