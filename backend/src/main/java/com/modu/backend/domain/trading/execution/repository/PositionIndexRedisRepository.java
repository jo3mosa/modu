package com.modu.backend.domain.trading.execution.repository;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

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

    /**
     * 268 backfill — 종목별 사용자 집합 일괄 SADD. Redis pipeline 으로 RTT 절감.
     * 부분 실패 시에도 다른 종목 진행. 종목 단위 실패는 ERROR 로그.
     */
    public void addUsersBatch(Map<String, ? extends Collection<Long>> stockToUsers) {
        if (stockToUsers == null || stockToUsers.isEmpty()) return;
        try {
            redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                stockToUsers.forEach((stockCode, userIds) -> {
                    if (userIds == null || userIds.isEmpty()) return;
                    String[] values = userIds.stream().map(Object::toString).toArray(String[]::new);
                    connection.setCommands().sAdd(key(stockCode).getBytes(), toByteArrays(values));
                });
                return null;
            });
        } catch (Exception e) {
            log.error("[PositionIndex] batch SADD 실패 - stockCount: {}", stockToUsers.size(), e);
        }
    }

    /**
     * 268 단계 4 검증 — 특정 종목의 현재 사용자 집합 조회 (KIS 잔고와 비교용)
     */
    public Set<String> getUsers(String stockCode) {
        try {
            Set<String> members = redisTemplate.opsForSet().members(key(stockCode));
            return members == null ? Set.of() : members;
        } catch (Exception e) {
            log.error("[PositionIndex] SMEMBERS 실패 - stockCode: {}", stockCode, e);
            return Set.of();
        }
    }

    private static byte[][] toByteArrays(String[] values) {
        byte[][] result = new byte[values.length][];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i].getBytes();
        }
        return result;
    }

    private static String key(String stockCode) {
        return KEY_PREFIX + stockCode;
    }
}
