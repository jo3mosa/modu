package com.modu.backend.domain.trading.calendar.bootstrap;

import com.modu.backend.domain.trading.calendar.service.TradingCalendarRefreshService;
import com.modu.backend.domain.trading.calendar.service.TradingCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 거래일 캘린더 부트스트랩 — S14P31B106-336
 *
 * 부팅 시 캐시가 비어있으면 KIS 호출 1회 실행. 신규 환경 / 마이그레이션 직후 / 운영자 수동 truncate
 * 등의 상황에서 다음 04:00 까지 기다리지 않고 즉시 캐시 채움.
 *
 * 캐시에 row 가 있으면 skip — 부팅마다 KIS 호출하지 않음.
 *
 * [로컬 디버깅 토글]
 *  modu.trading-calendar-refresh.enabled=false 로 비활성화 (스케줄러와 동일 키 공유).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "modu.trading-calendar-refresh.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TradingCalendarBootstrap implements ApplicationRunner {

    private final TradingCalendarService tradingCalendarService;
    private final TradingCalendarRefreshService refreshService;

    @Override
    public void run(ApplicationArguments args) {
        if (!tradingCalendarService.isCacheEmpty()) {
            log.info("[거래일 캘린더 부트스트랩] 기존 캐시 존재 - 갱신 skip");
            return;
        }
        log.info("[거래일 캘린더 부트스트랩] 캐시 비어있음 - 초기 1회 갱신 진행");
        refreshService.refresh();
    }
}
