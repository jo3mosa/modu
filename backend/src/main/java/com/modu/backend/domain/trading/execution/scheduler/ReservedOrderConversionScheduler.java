package com.modu.backend.domain.trading.execution.scheduler;

import com.modu.backend.domain.trading.execution.service.ReservedOrderConversionSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 예약주문 변환 모니터링 스케줄러 — S14P31B106-291
 *
 * [주기]
 *  매일 09:05 KST — 정규장 시작(09:00) 직후 5분 여유.
 *  KIS 가 예약주문을 일반 주문으로 변환하는 시점은 09:00 직후이고, 우리는 변환된 새 ODER_NO 를
 *  orders.kis_order_no 에 채워 후속 H0STCNI0 체결 통보 매칭을 가능하게 한다.
 *
 * [실패 처리]
 *  ReservedOrderConversionSyncService 가 예외를 흡수 — 스케줄러는 다음날 자동 재시도.
 *
 * [로컬 디버깅 토글]
 *  modu.reserved-conversion-sync.enabled=false 로 비활성화 가능 (기본 true).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "modu.reserved-conversion-sync.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservedOrderConversionScheduler {

    private final ReservedOrderConversionSyncService syncService;

    @Scheduled(cron = "0 5 9 * * *", zone = "Asia/Seoul")
    public void runDailySync() {
        log.info("[예약주문 변환 스케줄러] 트리거");
        try {
            syncService.sync();
        } catch (Exception e) {
            log.error("[예약주문 변환 스케줄러] 사이클 실패", e);
        }
    }
}
