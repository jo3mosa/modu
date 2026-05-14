package com.modu.backend.domain.trading.position.service;

import com.modu.backend.domain.trading.position.entity.PositionThreshold;
import com.modu.backend.domain.trading.position.entity.PositionTriggerReason;
import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Position Monitor 단일 사이클 평가 서비스
 *
 * [흐름]
 *  1. is_active=TRUE 인 PositionThreshold 일괄 조회
 *  2. 각 row 별로 Redis 락 시도 → 실패 시 skip
 *  3. Redis 시세 조회 (market:price:{stockCode}) → null 이면 skip
 *  4. 트리거 판정 (손절 우선 → 익절)
 *  5. 트리거 시 PositionTriggerExecutor.execute (별도 @Transactional)
 *  6. finally 락 해제
 *
 * [트랜잭션 경계]
 *  본 클래스는 non-transactional. 트랜잭션은 PositionTriggerExecutor.execute 안에서만 시작/종료.
 *  락을 트랜잭션 밖에서 잡고 푸는 구조로 락 보유 시간 ≈ 트랜잭션 시간 + Kafka 발행 시간.
 *
 * [매칭 정책]
 *  active_*_price 가 user_*_price 와 같으면 USER_*, 그 외(또는 양쪽 동률) USER_* 우선.
 *  ai_*_price 가 더 보수적인 경우만 AI_*.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionMonitorService {

    private static final String PRICE_KEY_PREFIX = "market:price:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final PositionThresholdRepository positionThresholdRepository;
    private final PositionTriggerLockService lockService;
    private final PositionTriggerExecutor triggerExecutor;
    private final StringRedisTemplate redisTemplate;

    public void evaluateAll() {
        List<PositionThreshold> positions = positionThresholdRepository.findAllActiveForMonitor();
        if (positions.isEmpty()) return;

        for (PositionThreshold position : positions) {
            evaluateOne(position);
        }
    }

    private void evaluateOne(PositionThreshold position) {
        Long userId = position.getUserId();
        String stockCode = position.getStockCode();

        if (!lockService.tryLock(userId, stockCode, LOCK_TTL)) {
            return;
        }

        try {
            Long currentPrice = readCurrentPrice(stockCode);
            if (currentPrice == null) return;

            PositionTriggerReason reason = resolveTriggerReason(position, currentPrice);
            if (reason == null) return;

            triggerExecutor.execute(position.getId(), reason);

        } catch (Exception e) {
            log.error("Position 평가 실패 - userId: {}, stockCode: {}", userId, stockCode, e);
        } finally {
            lockService.unlock(userId, stockCode);
        }
    }

    private Long readCurrentPrice(String stockCode) {
        try {
            String raw = redisTemplate.opsForValue().get(PRICE_KEY_PREFIX + stockCode);
            if (raw == null || raw.isBlank()) return null;
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("실시간 시세 파싱 실패 - stockCode: {}", stockCode);
            return null;
        } catch (Exception e) {
            log.error("실시간 시세 Redis 조회 실패 - stockCode: {}", stockCode, e);
            return null;
        }
    }

    /**
     * 트리거 판정 — 손절 우선 (위험 차단), 익절 그 다음
     * 동률 / 사용자 임계가 활성 임계 → USER_*, 그 외 AI_*
     */
    private PositionTriggerReason resolveTriggerReason(PositionThreshold p, long price) {
        Long stop = p.getActiveStopLossPrice();
        if (stop != null && price <= stop) {
            return matchesUser(stop, p.getUserStopLossPrice())
                    ? PositionTriggerReason.USER_STOP_LOSS
                    : PositionTriggerReason.AI_STOP_LOSS;
        }
        Long target = p.getActiveTargetPrice();
        if (target != null && price >= target) {
            return matchesUser(target, p.getUserTakeProfitPrice())
                    ? PositionTriggerReason.USER_TAKE_PROFIT
                    : PositionTriggerReason.AI_TAKE_PROFIT;
        }
        return null;
    }

    /**
     * active 임계 == 사용자 임계 이면 사용자 임계가 발동된 것으로 간주.
     * active = min(user, ai) 이므로 user 가 더 보수적 또는 user==ai 일 때 true.
     */
    private boolean matchesUser(long activePrice, Long userPrice) {
        return userPrice != null && activePrice == userPrice;
    }
}
