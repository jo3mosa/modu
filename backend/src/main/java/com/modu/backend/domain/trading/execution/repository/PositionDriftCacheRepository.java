package com.modu.backend.domain.trading.execution.repository;

import java.time.Duration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 외부 거래 drift dedup 캐시 — S14P31B106-361 (followups 2.10 A-1)
 *
 * [목적]
 *  PositionIndexKisVerificationService 가 1h 주기로 drift 를 감지하므로, 같은 drift 가
 *  해소될 때까지 매 주기 WARN 로그가 반복 발생. dedup 으로 첫 발견만 WARN, 재발견은 DEBUG.
 *
 * [Redis 키]
 *  drift:position:{direction}:{userId}:{stockCode}
 *  값: "1" (단순 존재 여부 — EXISTS 체크). TTL: 25h (다음 1h 주기보다 길게)
 *
 * [실패 처리]
 *  ERROR 로그만, throw X. caller (verifyAll) 가 다른 사용자 처리에 영향 주지 않도록.
 *  Redis 미작동 시 dedup 효과 없음 (= 매 주기 WARN) 으로 fallback — 동작 자체는 영향 X.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PositionDriftCacheRepository {

    private static final String KEY_PREFIX = "drift:position:";
    private static final Duration TTL = Duration.ofHours(25);
    private static final String MARKER_VALUE = "1";

    private final StringRedisTemplate redisTemplate;

    public boolean isAlreadyDetected(Long userId, String stockCode, PositionDriftDirection direction) {
        try {
            Boolean exists = redisTemplate.hasKey(key(userId, stockCode, direction));
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("[PositionDrift] EXISTS 실패 - userId: {}, stockCode: {}, direction: {}",
                    userId, stockCode, direction, e);
            return false;
        }
    }

    public void markDetected(Long userId, String stockCode, PositionDriftDirection direction) {
        try {
            redisTemplate.opsForValue().set(key(userId, stockCode, direction), MARKER_VALUE, TTL);
        } catch (Exception e) {
            log.error("[PositionDrift] SET 실패 - userId: {}, stockCode: {}, direction: {}",
                    userId, stockCode, direction, e);
        }
    }

    /**
     * drift 해소 처리. 실제 삭제 여부 반환 (true = 이전에 detected 였음 → 회복 로그 트리거 가능).
     */
    public boolean clearDetected(Long userId, String stockCode, PositionDriftDirection direction) {
        try {
            Boolean deleted = redisTemplate.delete(key(userId, stockCode, direction));
            return Boolean.TRUE.equals(deleted);
        } catch (Exception e) {
            log.error("[PositionDrift] DEL 실패 - userId: {}, stockCode: {}, direction: {}",
                    userId, stockCode, direction, e);
            return false;
        }
    }

    private static String key(Long userId, String stockCode, PositionDriftDirection direction) {
        return KEY_PREFIX + direction.name() + ":" + userId + ":" + stockCode;
    }
}
