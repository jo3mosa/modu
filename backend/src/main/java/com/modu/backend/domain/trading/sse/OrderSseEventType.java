package com.modu.backend.domain.trading.sse;

/**
 * 주문 처리 SSE 이벤트 타입
 *
 * 프론트엔드는 단일 SSE event name "order" 로 구독하고 본 enum 의 name() 으로 종류를 구분한다.
 *
 *  - ORDER_SUBMITTED        : KisOrderConsumer 가 KIS 일반 주문 호출에 성공한 시점 (kisOrderNo 발급 완료)
 *  - ORDER_FAILED           : KisOrderConsumer 가 KIS 호출 실패로 REJECTED 처리한 시점
 *  - ORDER_EXECUTED         : KIS 체결 통보 수신 후 처리 완료 시점 (S14P31B106-291 에서 사용)
 *  - ORDER_RESERVED         : KIS 예약주문 접수 성공 시점 (S14P31B106-336, RSVN_ORD_SEQ 발급 완료)
 *  - ORDER_RESERVED_PENDING : 예약 가능 시간 도래 전 RESERVED_PENDING 진입 시점 (E gap / 공휴일 정규장)
 */
public enum OrderSseEventType {
    ORDER_SUBMITTED,
    ORDER_FAILED,
    ORDER_EXECUTED,
    ORDER_RESERVED,
    ORDER_RESERVED_PENDING
}
