package com.modu.backend.domain.trading.calendar.policy;

import com.modu.backend.domain.trading.calendar.service.TradingCalendarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 시간/개장일 기반 라우팅 정책 — S14P31B106-336
 *
 * 자동매매 결정이 KisOrderConsumer 진입 시 어떤 흐름을 타야 할지 단일 진입점으로 분기.
 *
 * [분류]
 *  - REGULAR                       — 즉시 일반 주문 (KIS placeOrder)
 *  - WAITING_FOR_RESERVED_WINDOW   — RESERVED_PENDING 으로 두고 15:40 도래 시 스케줄러가 예약주문 발행
 *  - RESERVED_AVAILABLE            — 즉시 KIS 예약주문 발행 (placeReservedOrder)
 *  - REJECT                        — 주문 가능 시간 외 (REJECTED 처리)
 *
 * [구간 정의 — KIS 명세 기준]
 *  정규장               09:00 ~ 15:30   (개장일에 한함)
 *  E gap (대기)         15:30 ~ 15:40
 *  예약 가능 (오후)     15:40 ~ 23:40
 *  시스템 초기화 (X)    23:40 ~ 00:10
 *  예약 가능 (새벽)     00:10 ~ 07:30
 *  F gap (REJECT)       07:30 ~ 09:00
 *
 * [공휴일 / 주말]
 *  isMarketOpen=false 인 날은 어차피 정규장이 없으므로 시스템 초기화(23:40~00:10) 외 시간은
 *  모두 예약주문 흐름 (가능 시간이면 즉시, 아니면 대기) 으로 라우팅.
 *  KIS 가 RSVN_ORD_END_DT 빈 값일 때 다음 거래일에 자동 실행.
 *
 * [F gap (평일 07:30~09:00) 비대칭 처리 — REJECT]
 *  곧 정규장 시작 (최대 90분 후) 이라 RESERVED_PENDING 으로 대기 시 갭리스크 큼.
 *  사용자 결정에 따라 REJECT (옵션 1). 공휴일은 정규장이 없어 동일 시간대도 대기로 분류.
 *
 * [경계값 정책 — start 포함, end 제외]
 *  isBetween(t, A, B) ↔ A ≤ t < B
 */
@Component
@RequiredArgsConstructor
public class MarketHourPolicy {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static final LocalTime REGULAR_OPEN          = LocalTime.of(9, 0);
    public static final LocalTime REGULAR_CLOSE         = LocalTime.of(15, 30);
    public static final LocalTime RESERVED_AFTERNOON    = LocalTime.of(15, 40);
    public static final LocalTime SYSTEM_RESET_START    = LocalTime.of(23, 40);
    public static final LocalTime SYSTEM_RESET_END      = LocalTime.of(0, 10);
    public static final LocalTime RESERVED_MORNING_END  = LocalTime.of(7, 30);

    private final TradingCalendarService tradingCalendarService;

    public MarketHourPhase classify(OffsetDateTime now) {
        ZonedDateTime kst = now.atZoneSameInstant(KST);
        LocalTime time = kst.toLocalTime();
        LocalDate today = kst.toLocalDate();

        // 1. 시스템 초기화 시간대 — 23:40 ~ 24:00 + 00:00 ~ 00:10 (개장 여부 무관)
        if (!time.isBefore(SYSTEM_RESET_START) || time.isBefore(SYSTEM_RESET_END)) {
            return MarketHourPhase.REJECT;
        }

        // 2. 예약 가능 시간대 — 15:40 ~ 23:40, 00:10 ~ 07:30 (개장 여부 무관)
        if (isBetween(time, RESERVED_AFTERNOON, SYSTEM_RESET_START)
                || isBetween(time, SYSTEM_RESET_END, RESERVED_MORNING_END)) {
            return MarketHourPhase.RESERVED_AVAILABLE;
        }

        // 3. E gap — 15:30 ~ 15:40 (개장 여부 무관, 10분 후 예약 가능)
        if (isBetween(time, REGULAR_CLOSE, RESERVED_AFTERNOON)) {
            return MarketHourPhase.WAITING_FOR_RESERVED_WINDOW;
        }

        // 4. 정규장 / 공휴일 분기 — 남은 시간대는 07:30~09:00 (F gap) 와 09:00~15:30 (정규장 시간)
        boolean marketOpen = tradingCalendarService.isMarketOpen(today);

        if (isBetween(time, REGULAR_OPEN, REGULAR_CLOSE)) {
            // 정규장 시간대 — 개장일은 즉시 일반 주문, 공휴일은 다음 예약 가능 시간 대기
            return marketOpen ? MarketHourPhase.REGULAR : MarketHourPhase.WAITING_FOR_RESERVED_WINDOW;
        }

        // 5. F gap (07:30 ~ 09:00) — 평일이면 REJECT (곧 정규장), 공휴일이면 대기
        return marketOpen ? MarketHourPhase.REJECT : MarketHourPhase.WAITING_FOR_RESERVED_WINDOW;
    }

    /** start 포함 ~ end 제외 — KIS 시간 경계 표기와 일관. */
    private static boolean isBetween(LocalTime t, LocalTime start, LocalTime end) {
        return !t.isBefore(start) && t.isBefore(end);
    }
}
