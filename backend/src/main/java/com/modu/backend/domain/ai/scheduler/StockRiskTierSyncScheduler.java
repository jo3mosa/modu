package com.modu.backend.domain.ai.scheduler;

import com.modu.backend.domain.ai.service.StockRiskTierSyncService;
import com.modu.backend.domain.ai.service.StockRiskTierSyncService.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * stock:risk_tier 일일 재동기 스케줄러 — S14P31B106-356
 *
 * [실행 시각]
 *  매일 05:00 KST. 거래일 캘린더 갱신(04:00) 직후, 장 시작(09:00) 전.
 *  DA 1회 INSERT 영구 고정이지만 신규 종목 추가/누락 보정용 안전망.
 *
 * [실패 처리]
 *  SyncService 내부에서 catch — Scheduler 는 결과 로그만 남기고 throw 안 함.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRiskTierSyncScheduler {

    private final StockRiskTierSyncService stockRiskTierSyncService;

    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    public void runDailySync() {
        log.info("[StockRiskTierSyncScheduler] 일일 sync 트리거");
        SyncResult result = stockRiskTierSyncService.syncAll();
        if (!result.success()) {
            log.error("[StockRiskTierSyncScheduler] sync 실패 - {}", result.errorMessage());
            return;
        }
        log.info("[StockRiskTierSyncScheduler] sync 종료 - {}", result);
    }
}
