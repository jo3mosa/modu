package com.modu.backend.domain.trading.position.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Position Monitor 분산 락 서비스
 *
 * [동작]
 *  - 키:   lock:position:{userId}:{stockCode}
 *  - 메커니즘: SETNX(=setIfAbsent) + TTL 30초
 *  - 해제: Lua 스크립트로 "자기 소유 락" 만 DEL (TTL 만료 후 다른 스레드가 건 락 오삭제 방어)
 *
 * [목적]
 *  - 동일 (user, stock) 에 대한 폴링 사이클 중복 평가 방지
 *  - 영구 재진입 차단은 position_thresholds.is_active=FALSE 가 담당 — 본 락은 짧은 직렬화 가드
 *
 * [상태]
 *  - 락 token 을 in-process Map 으로 보관해 해제 시 매칭
 *  - 다중 인스턴스 시 인스턴스 간 락은 Redis 가 보장, 같은 인스턴스 안 token 매칭은 Map 으로 충분
 */
@Slf4j
@Component
public class PositionTriggerLockService {

    private static final String KEY_PREFIX = "lock:position:";

    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentMap<String, String> tokensByKey = new ConcurrentHashMap<>();

    public PositionTriggerLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 락 획득 시도 — 성공 시 token 보관, 실패 시 false
     *
     * @return true 면 획득. 호출자는 finally 블록에서 unlock 책임
     */
    public boolean tryLock(Long userId, String stockCode, Duration ttl) {
        String key = buildKey(userId, stockCode);
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
            if (Boolean.TRUE.equals(acquired)) {
                tokensByKey.put(key, token);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Position 락 획득 실패 - userId: {}, stockCode: {}", userId, stockCode, e);
            return false;
        }
    }

    /**
     * 자기 소유 락만 해제 — Lua 스크립트로 token 매칭 후 DEL
     * 보관된 token 이 없거나 Redis 측 token 이 다르면 noop (TTL 만료 → 다른 트랜잭션이 락 다시 잡은 케이스)
     */
    public void unlock(Long userId, String stockCode) {
        String key = buildKey(userId, stockCode);
        String token = tokensByKey.remove(key);
        if (token == null) return;
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        } catch (Exception e) {
            log.error("Position 락 해제 실패 - userId: {}, stockCode: {}", userId, stockCode, e);
        }
    }

    private String buildKey(Long userId, String stockCode) {
        return KEY_PREFIX + userId + ":" + stockCode;
    }
}
