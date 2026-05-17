package com.modu.backend.domain.trading.calendar.scheduler;

import com.modu.backend.domain.trading.calendar.service.TradingCalendarRefreshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 거래일 캘린더 일일 갱신 스케줄러 — S14P31B106-336
 *
 * [주기]
 *  cron "0 0 4 * * *" Asia/Seoul — 매일 04:00 KST. 정규장 시작(09:00) 전 충분한 여유 + 새벽 DB 백업 시간대(통상 03:00) 회피.
 *
 * [실패 처리]
 *  TradingCalendarRefreshService 가 예외를 흡수하고 로그만 남김. 스케줄러는 다음날 자동 재시도.
 *
 * [로컬 디버깅 토글]
 *  modu.trading-calendar-refresh.enabled=false 로 비활성화 가능 (기본 true).
 *  로컬에서 KIS 호출 방지 또는 다른 도메인 디버깅 시 사용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "modu.trading-calendar-refresh.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TradingCalendarRefreshScheduler {

    private final TradingCalendarRefreshService refreshService;

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void runDailyRefresh() {
        log.info("[거래일 캘린더 스케줄러] 일일 갱신 트리거");
        refreshService.refresh();
    }
}
