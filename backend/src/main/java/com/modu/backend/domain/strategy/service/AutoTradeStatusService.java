package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.investment.exception.InvestmentErrorCode;
import com.modu.backend.domain.investment.repository.InvestmentProfileRepository;
import com.modu.backend.domain.strategy.dto.AutoTradeStatusRequest;
import com.modu.backend.domain.strategy.dto.AutoTradeStatusResponse;
import com.modu.backend.domain.strategy.entity.AutoTradeSettings;
import com.modu.backend.domain.strategy.entity.AutoTradeStatus;
import com.modu.backend.domain.strategy.exception.StrategyErrorCode;
import com.modu.backend.domain.strategy.repository.AutoTradeSettingsRepository;
import com.modu.backend.domain.trading.repository.TradingRuleRepository;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 자동매매 상태 관리 서비스 (S14P31B106-292)
 *
 * [엔드포인트]
 *  PATCH /api/v1/strategies/me/status — isActive boolean 토글
 *
 * [선행 검증 (isActive=true 시만 적용)]
 *  1. KIS 연동 — KisCredential 존재 → 없으면 KIS_NOT_CONNECTED (USER_002, 명세상 KIS_003)
 *  2. 투자 성향 입력 — InvestmentProfile 존재 → 없으면 PROFILE_NOT_FOUND (INVEST_001, 명세상 STRATEGY_004)
 *  3. 리스크 룰셋 설정 — TradingRule 존재 → 없으면 RULE_NOT_FOUND (STRATEGY_005)
 *
 * [상태 전이]
 *  - ACTIVE/INACTIVE/KILL_SWITCHED + isActive=true  → ACTIVE
 *  - ACTIVE/INACTIVE/KILL_SWITCHED + isActive=false → INACTIVE (KILL_SWITCHED 인 경우 kill switch 기록 클리어)
 *  - 신규 사용자 (row 없음) → 첫 PATCH 시 row INSERT
 *
 * [Redis 동기화]
 *  Key:   auto-trade:status:{userId}
 *  Value: ACTIVE / INACTIVE / KILL_SWITCHED
 *  TTL:   없음 (영구). 재시작 시 DB 에서 복구 (별도 이슈)
 *  발행 시점: DB commit 후 (TransactionSynchronization.afterCommit) — commit 전 갱신 시 롤백 후 Redis 만 갱신될 위험
 *
 * [응답]
 *  AutoTradeStatusResponse — isActive boolean + updatedAt
 *  KILL_SWITCHED 는 외부에서 false 로 매핑 (해당 상태는 다른 흐름 — 사용자 ON 요청 시 해제됨)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoTradeStatusService {

    private static final String REDIS_KEY_PREFIX = "auto-trade:status:";

    private final AutoTradeSettingsRepository autoTradeSettingsRepository;
    private final KisCredentialRepository kisCredentialRepository;
    private final InvestmentProfileRepository investmentProfileRepository;
    private final TradingRuleRepository tradingRuleRepository;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public AutoTradeStatusResponse updateStatus(Long userId, AutoTradeStatusRequest request) {
        boolean isActive = request.isActive();

        if (isActive) {
            validatePrerequisites(userId);
        }

        AutoTradeSettings settings = autoTradeSettingsRepository.findById(userId)
                .orElseGet(() -> createInitialSettings(userId));

        if (isActive) {
            settings.activate();
        } else {
            settings.inactivate();
        }

        // commit 후 Redis 갱신 — commit 전 갱신 시 롤백 후 Redis 만 변경된 불일치 위험
        registerAfterCommitRedisSync(userId, settings.getAutoTradeStatus());

        log.info("자동매매 상태 변경 - userId: {}, isActive: {}, status: {}",
                userId, isActive, settings.getAutoTradeStatus());

        return AutoTradeStatusResponse.from(settings);
    }

    /**
     * 신규 사용자 초기 row INSERT — 동시 첫 요청 TOCTOU 가드
     *
     * findById + save 사이에 race 가 발생해 두 요청 모두 save 시도 시 PK 충돌(DataIntegrityViolationException)
     * 발생. catch 후 재조회로 회수.
     */
    private AutoTradeSettings createInitialSettings(Long userId) {
        try {
            return autoTradeSettingsRepository.save(
                    AutoTradeSettings.builder()
                            .userId(userId)
                            .autoTradeStatus(AutoTradeStatus.INACTIVE)
                            .build()
            );
        } catch (DataIntegrityViolationException e) {
            log.info("AutoTradeSettings 동시 INSERT 충돌 — 기존 row 재조회 - userId: {}", userId);
            return autoTradeSettingsRepository.findById(userId)
                    .orElseThrow(() -> e);
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // 선행 검증
    // ───────────────────────────────────────────────────────────────────

    private void validatePrerequisites(Long userId) {
        if (kisCredentialRepository.findByUserId(userId).isEmpty()) {
            throw new ApiException(UserErrorCode.KIS_NOT_CONNECTED);
        }
        if (investmentProfileRepository.findById(userId).isEmpty()) {
            throw new ApiException(InvestmentErrorCode.PROFILE_NOT_FOUND);
        }
        if (tradingRuleRepository.findById(userId).isEmpty()) {
            throw new ApiException(StrategyErrorCode.RULE_NOT_FOUND);
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Redis 동기화 — After Commit hook
    // ───────────────────────────────────────────────────────────────────

    private void registerAfterCommitRedisSync(Long userId, AutoTradeStatus status) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            syncToRedis(userId, status);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                syncToRedis(userId, status);
            }
        });
    }

    private void syncToRedis(Long userId, AutoTradeStatus status) {
        try {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + userId, status.name());
        } catch (Exception e) {
            log.error("자동매매 상태 Redis 동기화 실패 - userId: {}, status: {} (orphan: DB 만 갱신됨)",
                    userId, status, e);
        }
    }
}
