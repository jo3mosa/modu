package com.modu.backend.domain.trading.execution.runner;

import com.modu.backend.domain.trading.execution.service.PositionIndexBackfillService;
import com.modu.backend.domain.trading.execution.service.PositionIndexBackfillService.BackfillResult;
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
 * 268 단계 5 — 부팅 시 position:index 휘발 감지 + DB 기반 backfill
 *
 * [감지 로직]
 *  SCAN MATCH "position:index:stock:*" COUNT 1 — 1건이라도 있으면 skip (BE 단독 재시작 가정)
 *  0건이면 Redis 전체 휘발 (또는 최초 부팅) 으로 판단 → backfill 실행
 *
 * [portfolio:snapshot]
 *  본 Runner 는 portfolio:snapshot 을 직접 채우지 않음. KIS 호출 부담이 크므로 별도 비동기 작업
 *  (PositionIndexKisVerificationService) 가 부팅 후 initialDelay 로 처리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioBackfillStartupRunner implements ApplicationRunner {

    private static final String POSITION_INDEX_PATTERN = "position:index:stock:*";

    private final StringRedisTemplate redisTemplate;
    private final PositionIndexBackfillService positionIndexBackfillService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean cachePresent = anyKeyMatches(POSITION_INDEX_PATTERN);
            if (cachePresent) {
                log.info("[PortfolioBackfillStartup] position:index 이미 존재 — backfill skip");
                return;
            }
            log.info("[PortfolioBackfillStartup] position:index 비어있음 — DB → Redis backfill 실행");
            BackfillResult result = positionIndexBackfillService.backfillFromDb();
            log.info("[PortfolioBackfillStartup] 완료 — {}", result);
        } catch (Exception e) {
            log.error("[PortfolioBackfillStartup] 부팅 backfill 실패 — 후속 1h 주기 검증에서 복구 시도", e);
        }
    }

    /** SCAN 으로 패턴 매칭 키 1건이라도 존재하는지 확인 (KEYS 사용 금지 — 운영 Redis blocking) */
    private boolean anyKeyMatches(String pattern) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(1).build();
        return Boolean.TRUE.equals(redisTemplate.execute((RedisConnection connection) -> {
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                return cursor.hasNext();
            } catch (Exception e) {
                log.warn("[PortfolioBackfillStartup] SCAN 실패 — backfill 진행으로 폴백", e);
                return false;
            }
        }));
    }
}
