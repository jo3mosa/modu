package com.modu.backend.domain.trading.dto;

import java.util.List;

/**
 * 미체결 주문 조회 응답 DTO
 *
 * KIS 실시간 데이터(stockName, filledQuantity, remainQuantity)와
 * DB 데이터(orderId, side, orderType, source, createdAt)를 병합한 결과.
 */
public record PendingOrdersResponse(List<PendingOrderItem> pendingOrders) {

    /**
     * 미체결 주문 개별 항목
     *
     * [데이터 출처]
     * KIS inquire-psbl-rvsecncl:
     *   - stockCode, stockName, side, orderType, quantity, price, filledQuantity, remainQuantity
     * 우리 DB (orders 테이블):
     *   - orderId, source, createdAt
     *   (DB 미매칭 시 orderId/source/createdAt 은 null)
     */
    public record PendingOrderItem(
            String orderId,          // 우리 시스템 주문 ID (DB orders.id)
            String stockCode,        // 종목코드
            String stockName,        // 종목명 (KIS prdt_name)
            String side,             // BUY / SELL
            String orderType,        // LIMIT / MARKET
            Integer quantity,        // 주문수량
            Long price,              // 주문단가
            Integer filledQuantity,  // 총체결수량 (KIS tot_ccld_qty)
            Integer remainQuantity,  // 미체결잔량 (KIS psbl_qty)
            String source,           // AUTO / MANUAL (DB orders.source)
            String createdAt         // 주문 일시 (DB orders.created_at)
    ) {}
}
