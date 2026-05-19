package com.modu.backend.domain.investment.runner;

import com.modu.backend.domain.investment.service.UsersByGradeBackfillService;
import com.modu.backend.domain.investment.service.UsersByGradeBackfillService.BackfillResult;
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
 * 부팅 시 users:by_grade:* 휘발 감지 + DB 기반 backfill — S14P31B106-357
 *
 * 268 / 356 패턴 동일 — SCAN MATCH COUNT 1 으로 0건이면 backfillAll().
 * SCAN execute 자체 예외도 false 폴백으로 통일 (356 PR 피드백 반영).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsersByGradeBackfillStartupRunner implements ApplicationRunner {

    private static final String KEY_PATTERN = "users:by_grade:*";

    private final StringRedisTemplate redisTemplate;
    private final UsersByGradeBackfillService usersByGradeBackfillService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean cachePresent = anyKeyMatches(KEY_PATTERN);
            if (cachePresent) {
                log.info("[UsersByGradeBackfillStartup] users:by_grade 이미 존재 — backfill skip");
                return;
            }
            log.info("[UsersByGradeBackfillStartup] users:by_grade 비어있음 — DB → Redis backfill 실행");
            BackfillResult result = usersByGradeBackfillService.backfillAll();
            if (!result.success()) {
                log.error("[UsersByGradeBackfillStartup] backfill 실패 — {}", result);
                return;
            }
            log.info("[UsersByGradeBackfillStartup] 완료 — {}", result);
        } catch (Exception e) {
            log.error("[UsersByGradeBackfillStartup] 부팅 backfill 실패 — Scheduler 재시도 대기", e);
        }
    }

    private boolean anyKeyMatches(String pattern) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(1).build();
        try {
            return Boolean.TRUE.equals(redisTemplate.execute((RedisConnection connection) -> {
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    return cursor.hasNext();
                } catch (Exception e) {
                    log.warn("[UsersByGradeBackfillStartup] SCAN 실패 — backfill 진행으로 폴백", e);
                    return false;
                }
            }));
        } catch (Exception e) {
            log.warn("[UsersByGradeBackfillStartup] execute 실패 — backfill 진행으로 폴백", e);
            return false;
        }
    }
}
