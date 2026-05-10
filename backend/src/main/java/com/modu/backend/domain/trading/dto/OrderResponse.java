package com.modu.backend.domain.trading.dto;

import com.modu.backend.domain.trading.entity.Order;

import java.time.format.DateTimeFormatter;

public record OrderResponse(
        String orderId,
        String stockCode,
        String side,       // BUY / SELL
        String orderType,  // LIMIT / MARKET
        Integer quantity,
        Long price,
        String status,
        String createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                String.valueOf(order.getId()),
                order.getStockCode(),
                order.getSide().name(),
                order.getOrderType().name(),
                order.getQuantity().intValue(),
                order.getLimitPrice(),
                order.getStatus().name(),
                order.getCreatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );
    }
}
