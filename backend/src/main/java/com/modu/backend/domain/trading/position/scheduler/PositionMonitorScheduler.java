package com.modu.backend.domain.trading.position.scheduler;

import com.modu.backend.domain.trading.position.service.PositionMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Position Monitor 폴링 스케줄러 (S14P31B106-302)
 *
 * [주기]
 *  fixedDelay=2000 — 직전 사이클 종료 후 2초 대기 후 다음 사이클 시작 (병렬 사이클 방지)
 *
 * [장 시간 가드]
 *  KST 평일 09:00 ~ 15:30 외에는 스킵. 시세 캐시(304) 가 갱신되지 않아 비교 의미 없음.
 *  공휴일은 별도 가드 없음 — KIS 가 시세를 안 보내므로 market:price:* 가 갱신되지 않아 evaluateOne 단계에서 자연 skip.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PositionMonitorScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private final PositionMonitorService positionMonitorService;

    @Scheduled(fixedDelay = 2000L)
    public void runCycle() {
        if (!isMarketOpen()) return;
        try {
            positionMonitorService.evaluateAll();
        } catch (Exception e) {
            log.error("Position Monitor 사이클 실패", e);
        }
    }

    private boolean isMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime time = now.toLocalTime();
        return !time.isBefore(MARKET_OPEN) && time.isBefore(MARKET_CLOSE);
    }
}
