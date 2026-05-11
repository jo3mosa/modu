package com.modu.backend.domain.trading.dto;

public record OrderSseEvent(
        String type,       // ORDER_SUBMITTED(성공) / ORDER_FAILED(실패)
        String orderId,    // 우리 DB orders.id
        String stockCode,  // 종목 코드
        String kisOrderNo, // KIS 주문번호 (실패 시 null)
        String status,     // SUBMITTED / FAILED
        String message     // 사용자에게 보여줄 메시지
) {}
