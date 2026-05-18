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
 * - type        : 이벤트 종류 (ORDER_SUBMITTED / ORDER_FAILED / ORDER_EXECUTED / ORDER_RESERVED / ORDER_RESERVED_PENDING)
 * - orderId     : 우리 시스템 주문 ID (orders.id)
 * - stockCode   : 종목코드
 * - kisOrderNo  : KIS 접수번호. 예약주문은 RSVN_ORD_SEQ 가 동일 슬롯에 실림. 실패/대기 케이스에서는 null
 * - status      : 이벤트 시점 주문 상태 문자열 (PENDING / REJECTED / FILLED / RESERVED / RESERVED_PENDING)
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

    private static final String MSG_SUBMITTED        = "주문이 KIS에 접수되었습니다.";
    private static final String MSG_EXECUTED         = "주문이 체결되었습니다.";
    private static final String MSG_RESERVED         = "KIS 예약주문이 접수되었습니다. 다음 거래일에 주문이 실행됩니다.";
    private static final String MSG_RESERVED_PENDING = "예약 가능 시간 도래 시 자동으로 예약주문이 발행됩니다.";

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

    /**
     * KIS 예약주문 접수 성공 이벤트 (S14P31B106-336)
     *
     * @param orderId    orders.id
     * @param stockCode  종목코드
     * @param kisRsvnSeq KIS 발급 예약주문 순번 (kisOrderNo 슬롯에 실림)
     */
    public static OrderSseEvent reserved(String orderId, String stockCode, String kisRsvnSeq) {
        return new OrderSseEvent(
                OrderSseEventType.ORDER_RESERVED,
                orderId, stockCode, kisRsvnSeq,
                OrderStatus.RESERVED.name(),
                MSG_RESERVED,
                OffsetDateTime.now()
        );
    }

    /**
     * RESERVED_PENDING 진입 이벤트 (S14P31B106-336)
     *
     * @param orderId   orders.id
     * @param stockCode 종목코드
     */
    public static OrderSseEvent reservedPending(String orderId, String stockCode) {
        return new OrderSseEvent(
                OrderSseEventType.ORDER_RESERVED_PENDING,
                orderId, stockCode, null,
                OrderStatus.RESERVED_PENDING.name(),
                MSG_RESERVED_PENDING,
                OffsetDateTime.now()
        );
    }
}
