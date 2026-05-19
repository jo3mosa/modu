package com.modu.backend.domain.ai.runner;

import com.modu.backend.domain.ai.service.StockRiskTierSyncService;
import com.modu.backend.domain.ai.service.StockRiskTierSyncService.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 stock:risk_tier:* 휘발 감지 + DB 기반 backfill — S14P31B106-356
 *
 * 268 PortfolioBackfillStartupRunner 패턴 동일.
 *
 * [감지 로직]
 *  SCAN MATCH "stock:risk_tier:*" COUNT 1 — 1건이라도 있으면 skip
 *  0건이면 Redis 휘발 (또는 최초 부팅) 으로 판단 → syncAll() 실행
 *
 * [KEYS 금지]
 *  운영 Redis blocking 위험. 반드시 SCAN 사용.
 *
 * [실패 처리]
 *  SCAN/sync 예외는 ERROR 로그 후 swallow — 다음 부팅 또는 Scheduler 가 재시도.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRiskTierBackfillStartupRunner implements ApplicationRunner {

    private static final String KEY_PATTERN = "stock:risk_tier:*";

    private final StringRedisTemplate redisTemplate;
    private final StockRiskTierSyncService stockRiskTierSyncService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean cachePresent = anyKeyMatches(KEY_PATTERN);
            if (cachePresent) {
                log.info("[StockRiskTierBackfillStartup] stock:risk_tier 이미 존재 — backfill skip");
                return;
            }
            log.info("[StockRiskTierBackfillStartup] stock:risk_tier 비어있음 — DB → Redis backfill 실행");
            SyncResult result = stockRiskTierSyncService.syncAll();
            log.info("[StockRiskTierBackfillStartup] 완료 — {}", result);
        } catch (Exception e) {
            log.error("[StockRiskTierBackfillStartup] 부팅 backfill 실패 — Scheduler 재시도 대기", e);
        }
    }

    /** SCAN 으로 패턴 매칭 키 1건이라도 존재하는지 확인 (KEYS 사용 금지) */
    private boolean anyKeyMatches(String pattern) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(1).build();
        return Boolean.TRUE.equals(redisTemplate.execute((RedisConnection connection) -> {
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                return cursor.hasNext();
            } catch (Exception e) {
                log.warn("[StockRiskTierBackfillStartup] SCAN 실패 — backfill 진행으로 폴백", e);
                return false;
            }
        }));
    }
}
