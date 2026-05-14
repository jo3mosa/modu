package com.modu.backend.domain.trading.sse;

import com.modu.backend.domain.trading.entity.OrderStatus;

import java.time.OffsetDateTime;

/**
 * 주문 처리 SSE 이벤트 페이로드
 *
 * EventSource 가 헤더를 못 보내는 제약 + 단일 event name "order" 컨벤션 채택에 따라
 * 본 페이로드의 type 필드로 종류를 구분한다.
 *
 * [필드 의미]
 * - type        : 이벤트 종류 (ORDER_SUBMITTED / ORDER_FAILED / ORDER_EXECUTED)
 * - orderId     : 우리 시스템 주문 ID (orders.id)
 * - stockCode   : 종목코드
 * - kisOrderNo  : KIS 접수번호. 실패 케이스(ORDER_FAILED)에서는 null
 * - status      : 이벤트 시점 주문 상태 문자열 (PENDING / REJECTED / FILLED)
 * - message     : 사용자 표시용 메시지
 * - timestamp   : 이벤트 발생 시각
 */
public record OrderSseEvent(
        OrderSseEventType type,
        String            orderId,
        String            stockCode,
        String            kisOrderNo,
        String            status,
        String            message,
        OffsetDateTime    timestamp
) {

    private static final String MSG_SUBMITTED = "주문이 KIS에 접수되었습니다.";
    private static final String MSG_EXECUTED  = "주문이 체결되었습니다.";

    /**
     * KIS 접수 성공 이벤트
     *
     * @param orderId    orders.id
     * @param stockCode  종목코드
     * @param kisOrderNo KIS 발급 주문번호
     */
    public static OrderSseEvent submitted(String orderId, String stockCode, String kisOrderNo) {
        return new OrderSseEvent(
                OrderSseEventType.ORDER_SUBMITTED,
                orderId, stockCode, kisOrderNo,
                OrderStatus.PENDING.name(),
                MSG_SUBMITTED,
                OffsetDateTime.now()
        );
    }

    /**
     * KIS 접수 실패 이벤트
     *
     * @param orderId   orders.id
     * @param stockCode 종목코드
     * @param reason    사용자 표시용 실패 사유
     */
    public static OrderSseEvent failed(String orderId, String stockCode, String reason) {
        return new OrderSseEvent(
                OrderSseEventType.ORDER_FAILED,
                orderId, stockCode, null,
                OrderStatus.REJECTED.name(),
                reason,
                OffsetDateTime.now()
        );
    }

    /**
     * 체결 통보 이벤트 (S14P31B106-291 에서 사용)
     *
     * @param orderId    orders.id
     * @param stockCode  종목코드
     * @param kisOrderNo KIS 주문번호
     */
    public static OrderSseEvent executed(String orderId, String stockCode, String kisOrderNo) {
        return new OrderSseEvent(
                OrderSseEventType.ORDER_EXECUTED,
                orderId, stockCode, kisOrderNo,
                OrderStatus.FILLED.name(),
                MSG_EXECUTED,
                OffsetDateTime.now()
        );
    }
}
