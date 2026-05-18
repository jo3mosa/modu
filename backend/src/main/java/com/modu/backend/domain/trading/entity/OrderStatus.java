package com.modu.backend.domain.trading.entity;

public enum OrderStatus {
    /** KIS 일반 주문 접수 후 체결 대기 */
    PENDING,

    /** 체결 완료 */
    FILLED,

    /** 사용자/시스템 취소 */
    CANCELED,

    /** 정정 완료 (새 주문번호로 갱신) */
    MODIFIED,

    /** KIS 거부 또는 시스템 사전 검증 실패 */
    REJECTED,

    /**
     * KIS 예약주문 접수 완료 (S14P31B106-336)
     * 다음 거래일 정규장 시작 시 KIS 가 자동으로 일반 주문으로 전환.
     * orders.kis_rsvn_seq 에 RSVN_ORD_SEQ 보관.
     */
    RESERVED,

    /**
     * 예약주문 발행 대기 (S14P31B106-336)
     * E gap (15:30~15:40) 또는 공휴일 정규장 시간대 진입 시 본 상태로 보류.
     * ReservedPendingOrderSweeper 가 예약 가능 시간 도래를 감지하면 placeReservedOrder 호출.
     */
    RESERVED_PENDING
}
