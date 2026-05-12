package com.modu.backend.domain.trading.dto;

import java.util.List;

/**
 * 거래 이력 조회 응답 DTO
 *
 * KIS inquire-daily-ccld + DB orders 병합 결과.
 * DB 매칭(orders.kis_order_no = KIS odno) 시 orderId/source/createdAt 은 DB 값,
 * 미매칭 시 orderId/source 는 null, createdAt 은 KIS ord_dt+ord_tmd 조합 값.
 */
public record OrderHistoryResponse(
        List<OrderHistoryItem> orders,
        int totalCount,
        int page,
        int size
) {
    /**
     * 거래 이력 개별 항목
     *
     * [데이터 출처]
     * KIS inquire-daily-ccld:
     *   - stockCode, stockName, side, orderType, quantity, price, status
     * 우리 DB (orders 테이블):
     *   - orderId, source (DB 매칭 주문만 제공)
     * createdAt:
     *   - DB 매칭 시 orders.created_at (ISO_OFFSET_DATE_TIME)
     *   - 미매칭 시 KIS ord_dt+ord_tmd 조합 (KST, ISO_OFFSET_DATE_TIME)
     */
    public record OrderHistoryItem(
            String orderId,        // DB orders.id (미매칭 시 null)
            String stockCode,
            String stockName,
            String side,           // BUY / SELL
            String orderType,      // LIMIT / MARKET
            long quantity,
            long price,
            String status,         // FILLED / CANCELED / PENDING
            String source,         // AUTO / MANUAL (미매칭 시 null)
            String createdAt       // ISO_OFFSET_DATE_TIME
    ) {}
}
