package com.modu.backend.domain.market.service;

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
 * 분봉 캔들 KIS 호출 single-flight 분산 락 — S14P31B106-365
 *
 * 키:    lock:kis-minute-candle:{stockCode}
 * 메커니즘: SETNX + TTL + Lua 매칭 DEL (PositionTriggerLockService 패턴 차용)
 *
 * 목적:
 *  - 멀티 pod 환경에서 같은 종목 동시 KIS 호출 직렬화
 *  - KIS rate limit (실전 초당 20건) 부담 분산
 */
@Slf4j
@Component
public class KisMinuteCandleLockService {

    private static final String KEY_PREFIX = "lock:kis-minute-candle:";

    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentMap<String, String> tokensByKey = new ConcurrentHashMap<>();

    public KisMinuteCandleLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryLock(String stockCode, Duration ttl) {
        String key = buildKey(stockCode);
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
            if (Boolean.TRUE.equals(acquired)) {
                tokensByKey.put(key, token);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Minute candle 락 획득 실패 - stockCode: {}", stockCode, e);
            return false;
        }
    }

    public void unlock(String stockCode) {
        String key = buildKey(stockCode);
        String token = tokensByKey.remove(key);
        if (token == null) return;
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        } catch (Exception e) {
            log.error("Minute candle 락 해제 실패 - stockCode: {}", stockCode, e);
        }
    }

    private String buildKey(String stockCode) {
        return KEY_PREFIX + stockCode;
    }
}
