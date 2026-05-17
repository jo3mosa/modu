package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.strategy.entity.AutoTradeSettings;
import com.modu.backend.domain.strategy.entity.AutoTradeStatus;
import com.modu.backend.domain.strategy.repository.AutoTradeSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

/**
 * Kill Switch 자동 발동 서비스 (S14P31B106-292)
 *
 * [발동 조건]
 *  (user_id, stock_code) 조합으로 KIS 거부 5회 누적 시 자동 PAUSED (KILL_SWITCHED)
 *
 * [카운트 저장소]
 *  Key:   kis-reject-count:{userId}:{stockCode}
 *  Value: INCR 정수
 *  TTL:   1시간 — 매번 EXPIRE 갱신 (sliding window). 최근 1시간 활동이 5회 도달 시 발동
 *
 * [리셋]
 *  - 성공 시 즉시 DEL
 *  - 발동 시 즉시 DEL (재카운트 가능 — 사용자가 ACTIVE 로 켜고 다시 거부되면 새 카운트)
 *
 * [호출자]
 *  - 단계 6 (KisOrderConsumer) 가 REJECTED 처리 시 recordReject 호출 (source=AI_DECISION/STOP_LOSS/TAKE_PROFIT 만)
 *  - 단계 6 가 정상 접수 시 recordSuccess 호출
 *
 * [SSE 알림]
 *  본 PR 단계 10 에서 KILL_SWITCH_TRIGGERED SSE 이벤트 추가 예정
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KillSwitchService {

    private static final String COUNT_KEY_PREFIX = "kis-reject-count:";
    private static final String STATUS_KEY_PREFIX = "auto-trade:status:";
    private static final Duration COUNT_TTL = Duration.ofHours(1);
    private static final long TRIGGER_THRESHOLD = 5L;

    private final AutoTradeSettingsRepository autoTradeSettingsRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * KIS 거부 발생 시 호출. 카운트 +1, sliding TTL 갱신, 5회 도달 시 Kill Switch 발동.
     */
    @Transactional
    public void recordReject(Long userId, String stockCode, String reason) {
        if (userId == null || stockCode == null) return;

        String countKey = COUNT_KEY_PREFIX + userId + ":" + stockCode;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(countKey);
            redisTemplate.expire(countKey, COUNT_TTL);   // sliding window
        } catch (Exception e) {
            log.error("KIS 거부 카운트 INCR 실패 - userId: {}, stockCode: {}", userId, stockCode, e);
            return;
        }

        log.info("KIS 거부 카운트 누적 - userId: {}, stockCode: {}, count: {}/{}",
                userId, stockCode, count, TRIGGER_THRESHOLD);

        if (count != null && count >= TRIGGER_THRESHOLD) {
            triggerKillSwitch(userId, stockCode, reason);
            safeDel(countKey);   // 발동 후 카운트 리셋 (재카운트 가능)
        }
    }

    /**
     * KIS 정상 접수 시 호출. 해당 (user, stock) 카운트 리셋.
     */
    public void recordSuccess(Long userId, String stockCode) {
        if (userId == null || stockCode == null) return;
        safeDel(COUNT_KEY_PREFIX + userId + ":" + stockCode);
    }

    // ───────────────────────────────────────────────────────────────────
    // Kill Switch 발동
    // ───────────────────────────────────────────────────────────────────

    private void triggerKillSwitch(Long userId, String stockCode, String reason) {
        AutoTradeSettings settings = autoTradeSettingsRepository.findById(userId).orElse(null);
        if (settings == null) {
            // AutoTradeSettings row 없음 — 사용자가 자동매매 켜본 적 없음. 카운트 의미 없으나 안전상 무시
            log.warn("Kill Switch 발동 대상 AutoTradeSettings 없음 - userId: {}", userId);
            return;
        }
        if (settings.getAutoTradeStatus() == AutoTradeStatus.KILL_SWITCHED) {
            // 이미 발동된 상태 — 멱등
            return;
        }

        String killReason = String.format("KIS 거부 %d회 누적 (stock_code=%s) — %s",
                TRIGGER_THRESHOLD, stockCode, reason);
        settings.triggerKillSwitch(killReason);

        // commit 후 Redis 상태 동기화
        registerAfterCommitRedisSync(userId, AutoTradeStatus.KILL_SWITCHED);

        log.warn("Kill Switch 자동 발동 - userId: {}, reason: {}", userId, killReason);
    }

    // ───────────────────────────────────────────────────────────────────
    // 헬퍼
    // ───────────────────────────────────────────────────────────────────

    private void safeDel(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Redis DEL 실패 - key: {}", key, e);
        }
    }

    private void registerAfterCommitRedisSync(Long userId, AutoTradeStatus status) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            syncStatusToRedis(userId, status);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                syncStatusToRedis(userId, status);
            }
        });
    }

    private void syncStatusToRedis(Long userId, AutoTradeStatus status) {
        try {
            redisTemplate.opsForValue().set(STATUS_KEY_PREFIX + userId, status.name());
        } catch (Exception e) {
            log.error("Kill Switch 상태 Redis 동기화 실패 - userId: {}, status: {}", userId, status, e);
        }
    }
}
