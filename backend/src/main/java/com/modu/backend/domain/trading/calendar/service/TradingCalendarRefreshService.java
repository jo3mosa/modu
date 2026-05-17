package com.modu.backend.domain.trading.calendar.service;

import com.modu.backend.domain.market.service.KisPlatformTokenService;
import com.modu.backend.domain.trading.calendar.client.KisHolidayClient;
import com.modu.backend.domain.trading.calendar.entity.TradingCalendar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 거래일 캘린더 갱신 오케스트레이션 — S14P31B106-336
 *
 * KIS 호출 → upsert 의 흐름을 단일 진입점으로 묶음. 스케줄러 / 부트스트랩 / 운영자 수동 갱신 모두
 * 동일 메서드 호출. 실패 시 기존 캐시 보존 + ERROR 로그 — caller 는 정상 흐름 계속.
 *
 * [범위]
 *  오늘(KST 기준) 부터 TARGET_DAYS 일치. KisHolidayClient 가 페이지네이션으로 수집.
 *
 * [실패 격리]
 *  KIS 응답 오류 / RestClient 예외 발생 시 RuntimeException 으로 위로 던지지 않음 — log + return.
 *  스케줄러는 다음 사이클에 자동 재시도. 부트스트랩은 fallback (월~금 가정) 으로 계속 동작.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingCalendarRefreshService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 1회 갱신으로 보관할 일수 — 페이지네이션 약 4회 (KIS 응답 ~24일/페이지) */
    private static final int TARGET_DAYS = 90;

    private final KisPlatformTokenService kisPlatformTokenService;
    private final KisHolidayClient kisHolidayClient;
    private final TradingCalendarService tradingCalendarService;

    /**
     * 오늘(KST) 기준 향후 약 90일치 휴장일 정보 갱신.
     *
     * @return 정상 수신해 upsert 한 row 수. 실패 시 0.
     */
    public int refresh() {
        LocalDate from = LocalDate.now(KST);
        log.info("[거래일 캘린더] 갱신 시작 - from: {}, targetDays: {}", from, TARGET_DAYS);
        try {
            String token = kisPlatformTokenService.getAccessToken();
            List<TradingCalendar> incoming = kisHolidayClient.fetchUpcomingHolidays(token, from, TARGET_DAYS);
            if (incoming.isEmpty()) {
                log.warn("[거래일 캘린더] KIS 응답이 비어있음 - 갱신 skip");
                return 0;
            }
            int upserted = tradingCalendarService.upsertAll(incoming);
            log.info("[거래일 캘린더] 갱신 완료 - upserted: {}", upserted);
            return upserted;
        } catch (Exception e) {
            // 실패해도 기존 캐시 보존 — 부분 갱신은 upsertAll 트랜잭션 단위로 롤백
            log.error("[거래일 캘린더] 갱신 실패 - 기존 캐시 유지", e);
            return 0;
        }
    }
}
