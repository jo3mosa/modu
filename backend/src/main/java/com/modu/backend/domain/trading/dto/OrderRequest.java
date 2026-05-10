package com.modu.backend.domain.trading.dto;

import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 수동 주문 실행 요청 DTO
 *
 * side       → DB side      (BUY/SELL)
 * orderMethod → DB order_type (LIMIT/MARKET)
 *
 * [가격 검증]
 * - MARKET(시장가): price = 0 허용 (KIS 호출 시 ORD_UNPR=0 으로 고정)
 * - LIMIT(지정가):  price > 0 필수
 */
public record OrderRequest(
        @NotBlank String stockCode,
        @NotNull OrderSide side,
        @NotNull @Min(1) Integer quantity,
        @NotNull @Min(0) Long price,
        @NotNull OrderType orderMethod
) {
    @AssertTrue(message = "지정가(LIMIT) 주문 시 가격은 0보다 커야 합니다.")
    public boolean isPriceValidForOrderMethod() {
        if (orderMethod == null || price == null) return true;
        return orderMethod != OrderType.LIMIT || price > 0;
    }
}
