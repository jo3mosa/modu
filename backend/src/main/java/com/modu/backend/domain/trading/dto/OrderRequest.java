package com.modu.backend.domain.trading.dto;

import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 수동 주문 실행 요청 DTO
 *
 * orderType  → DB side (BUY/SELL)
 * orderMethod → DB order_type (LIMIT/MARKET)
 */
public record OrderRequest(
        @NotBlank String stockCode,
        @NotNull OrderSide orderType,
        @NotNull @Min(1) Integer quantity,
        @NotNull @Min(0) Long price,
        @NotNull OrderType orderMethod
) {}
