package com.modu.backend.domain.ai.repository;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis stock:risk_tier:{stock_code} String 접근 — S14P31B106-355
 *
 * AI 측 비보유자 매칭에 사용. daily_fundamentals.risk_tier 컬럼을 부팅 backfill (356) 또는
 * 운영자 수동 sync API (356) 가 Redis 에 일괄 적재.
 *
 * [TTL]
 *  없음 (영구). DA 측 1회 INSERT 영구 고정 정책.
 *
 * [실패 처리]
 *  ERROR 로그만. caller 가 throw 안 하도록 — 단건 실패가 backfill 전체를 막지 않게.
 *
 * [Tier 범위]
 *  1~5 외 값은 ERROR 로그 + early return.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class StockRiskTierRedisRepository {

    private static final String KEY_PREFIX = "stock:risk_tier:";
    private static final int MIN_TIER = 1;
    private static final int MAX_TIER = 5;

    private final StringRedisTemplate redisTemplate;

    public void save(String stockCode, int tier) {
        if (!isValidTier(tier)) {
            log.error("[StockRiskTier] tier 범위 벗어남 - stockCode: {}, tier: {}", stockCode, tier);
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(stockCode), Integer.toString(tier));
        } catch (Exception e) {
            log.error("[StockRiskTier] SET 실패 - stockCode: {}, tier: {}", stockCode, tier, e);
        }
    }

    /**
     * 356 backfill / 운영자 수동 sync — daily_fundamentals 일괄 적재. Redis pipeline 으로 RTT 절감.
     * 부분 실패 시에도 다른 종목 진행. 1~5 벗어난 값은 skip.
     */
    public void saveBatch(Map<String, Integer> stockToTier) {
        if (stockToTier == null || stockToTier.isEmpty()) return;
        var keySerializer = redisTemplate.getStringSerializer();
        try {
            redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                stockToTier.forEach((stockCode, tier) -> {
                    try {
                        if (tier == null || !isValidTier(tier)) {
                            log.error("[StockRiskTier] batch tier 범위 벗어남 - stockCode: {}, tier: {}", stockCode, tier);
                            return;
                        }
                        byte[] keyBytes = keySerializer.serialize(key(stockCode));
                        byte[] valueBytes = keySerializer.serialize(Integer.toString(tier));
                        if (keyBytes == null || valueBytes == null) {
                            log.error("[StockRiskTier] batch 직렬화 실패 - stockCode: {}", stockCode);
                            return;
                        }
                        connection.stringCommands().set(keyBytes, valueBytes);
                    } catch (Exception perEntry) {
                        log.error("[StockRiskTier] batch 단건 SET 실패 - stockCode: {}, tier: {}", stockCode, tier, perEntry);
                    }
                });
                return null;
            });
        } catch (Exception e) {
            log.error("[StockRiskTier] batch SET 실패 - stockCount: {}", stockToTier.size(), e);
        }
    }

    /**
     * 운영자 수동 sync 검증 / 디버깅용. 미설정 시 null.
     */
    public Integer get(String stockCode) {
        try {
            String value = redisTemplate.opsForValue().get(key(stockCode));
            return value == null ? null : Integer.parseInt(value);
        } catch (Exception e) {
            log.error("[StockRiskTier] GET 실패 - stockCode: {}", stockCode, e);
            return null;
        }
    }

    private static boolean isValidTier(int tier) {
        return tier >= MIN_TIER && tier <= MAX_TIER;
    }

    private static String key(String stockCode) {
        return KEY_PREFIX + stockCode;
    }
}
