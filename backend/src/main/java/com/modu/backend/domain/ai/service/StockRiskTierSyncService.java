package com.modu.backend.domain.ai.service;

import java.util.HashMap;
import java.util.Map;

import com.modu.backend.domain.ai.repository.StockRiskTierRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * daily_fundamentals.risk_tier → Redis stock:risk_tier:{code} 동기화 — S14P31B106-356
 *
 * 호출자:
 *  - StockRiskTierBackfillStartupRunner — 부팅 시 SCAN 으로 휘발 감지 후 1회
 *  - StockRiskTierSyncScheduler — 매일 05:00 KST 정기 재동기
 *
 * 쿼리:
 *  종목당 최신 (risk_tier NOT NULL) 1건. DA single writer 이고 BE 는 SELECT 만.
 *  PG-specific DISTINCT ON 사용 — PK (stock_code, date) 인덱스로 커버.
 *
 * 실패 처리:
 *  단건 실패는 saveBatch 내부에서 swallow. 전체 쿼리 실패는 ERROR 로그 + result.failed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockRiskTierSyncService {

    private static final String SELECT_LATEST_RISK_TIER = """
            SELECT DISTINCT ON (stock_code) stock_code, risk_tier
            FROM daily_fundamentals
            WHERE risk_tier IS NOT NULL
            ORDER BY stock_code, date DESC
            """;

    private final JdbcTemplate jdbcTemplate;
    private final StockRiskTierRedisRepository stockRiskTierRedisRepository;

    @Transactional(readOnly = true)
    public SyncResult syncAll() {
        long startMs = System.currentTimeMillis();
        Map<String, Integer> stockToTier;
        try {
            stockToTier = loadFromDb();
        } catch (Exception e) {
            log.error("[StockRiskTierSync] daily_fundamentals 조회 실패", e);
            return SyncResult.failed(e.getMessage());
        }

        if (stockToTier.isEmpty()) {
            log.warn("[StockRiskTierSync] daily_fundamentals 에 risk_tier 값 없음 — Redis 적재 skip");
            return SyncResult.empty();
        }

        try {
            stockRiskTierRedisRepository.saveBatch(stockToTier);
        } catch (Exception e) {
            log.error("[StockRiskTierSync] Redis 적재 실패 - 종목 {}건", stockToTier.size(), e);
            return SyncResult.failed(e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - startMs;
        log.info("[StockRiskTierSync] 완료 - 종목 {}건, 소요 {}ms", stockToTier.size(), elapsed);
        return SyncResult.success(stockToTier.size(), elapsed);
    }

    private Map<String, Integer> loadFromDb() {
        Map<String, Integer> result = new HashMap<>();
        jdbcTemplate.query(SELECT_LATEST_RISK_TIER, rs -> {
            String stockCode = rs.getString("stock_code");
            int tier = rs.getInt("risk_tier");
            result.put(stockCode, tier);
        });
        return result;
    }

    public record SyncResult(int count, long elapsedMs, boolean success, String errorMessage) {
        public static SyncResult success(int count, long elapsedMs) {
            return new SyncResult(count, elapsedMs, true, null);
        }
        public static SyncResult empty() {
            return new SyncResult(0, 0L, true, null);
        }
        public static SyncResult failed(String message) {
            return new SyncResult(0, 0L, false, message);
        }
    }
}
