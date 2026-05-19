package com.modu.backend.domain.investment.scheduler;

import com.modu.backend.domain.investment.service.UsersByGradeBackfillService;
import com.modu.backend.domain.investment.service.UsersByGradeBackfillService.BackfillResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * users:by_grade 일일 재동기 스케줄러 — S14P31B106-357
 *
 * [실행 시각]
 *  매일 05:30 KST. 356 stock:risk_tier sync(05:00) 와 분산.
 *  profile hook 으로 증분 유지되지만 hook 누락/실패 대비 안전망.
 *
 * [실패 처리]
 *  Service 내부 catch — Scheduler 는 결과 로그만 + 외부 try-catch (Javadoc 계약).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsersByGradeSyncScheduler {

    private final UsersByGradeBackfillService usersByGradeBackfillService;

    @Scheduled(cron = "0 30 5 * * *", zone = "Asia/Seoul")
    public void runDailySync() {
        log.info("[UsersByGradeSyncScheduler] 일일 sync 트리거");
        try {
            BackfillResult result = usersByGradeBackfillService.backfillAll();
            if (!result.success()) {
                log.error("[UsersByGradeSyncScheduler] sync 실패 - {}", result.errorMessage());
                return;
            }
            log.info("[UsersByGradeSyncScheduler] sync 종료 - {}", result);
        } catch (Exception e) {
            log.error("[UsersByGradeSyncScheduler] 예기치 못한 예외 — 다음 cron 재시도 대기", e);
        }
    }
}
