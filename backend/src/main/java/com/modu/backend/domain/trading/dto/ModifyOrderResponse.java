package com.modu.backend.domain.trading.dto;

import com.modu.backend.domain.trading.entity.Order;

import java.time.format.DateTimeFormatter;

/**
 * 미체결 주문 정정/취소 응답 DTO
 *
 * status: MODIFIED (정정 완료) / CANCELED (취소 완료)
 */
public record ModifyOrderResponse(
        String orderId,
        String status,
        String updatedAt
) {
    public static ModifyOrderResponse from(Order order) {
        return new ModifyOrderResponse(
                String.valueOf(order.getId()),
                order.getStatus().name(),
                order.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );
    }
}
