package com.modu.backend.domain.trading.calendar.policy;

/**
 * 자동매매 라우팅 분기 — S14P31B106-336
 *
 * KisOrderConsumer 가 메시지 수신 시 MarketHourPolicy.classify 결과를 받아 흐름 분기.
 */
public enum MarketHourPhase {
    /** 정규장 시간 — 즉시 KIS 일반 주문 (placeOrder) */
    REGULAR,

    /**
     * RESERVED_PENDING 으로 보류 후 스케줄러가 예약 가능 시간 도래 시 예약주문 발행.
     * 대상: 15:30~15:40 (E gap) / 공휴일 09:00~15:30 / 공휴일 07:30~09:00
     */
    WAITING_FOR_RESERVED_WINDOW,

    /** 15:40 ~ 23:40 또는 00:10 ~ 07:30 — 즉시 KIS 예약주문 (placeReservedOrder) */
    RESERVED_AVAILABLE,

    /** 23:40 ~ 00:10 (시스템 초기화) / 평일 07:30 ~ 09:00 (F gap) — 거절 */
    REJECT
}
