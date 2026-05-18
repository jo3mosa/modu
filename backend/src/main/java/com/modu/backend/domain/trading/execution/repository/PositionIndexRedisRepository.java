package com.modu.backend.domain.trading.execution.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis position:index:stock:{stock_code} Set 접근 — S14P31B106-291
 *
 * AI 측 `match_market_event_to_users` 가 읽는 키. BUY 체결 시 SADD, SELL 전량 시 SREM.
 *
 * [TTL]
 *  없음 (영구). 재시작 시 268 (Redis 재시작 복구) 으로 backfill.
 *
 * [실패 처리]
 *  ERROR 로그만. caller (AFTER_COMMIT listener) 가 throw 안 하도록 — DB commit 후 Redis 실패는
 *  268 의 backfill 대상.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PositionIndexRedisRepository {

    private static final String KEY_PREFIX = "position:index:stock:";

    private final StringRedisTemplate redisTemplate;

    public void addUser(String stockCode, Long userId) {
        try {
            redisTemplate.opsForSet().add(key(stockCode), userId.toString());
        } catch (Exception e) {
            log.error("[PositionIndex] SADD 실패 - stockCode: {}, userId: {}", stockCode, userId, e);
        }
    }

    public void removeUser(String stockCode, Long userId) {
        try {
            redisTemplate.opsForSet().remove(key(stockCode), userId.toString());
        } catch (Exception e) {
            log.error("[PositionIndex] SREM 실패 - stockCode: {}, userId: {}", stockCode, userId, e);
        }
    }

    private static String key(String stockCode) {
        return KEY_PREFIX + stockCode;
    }
}
