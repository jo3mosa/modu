package com.modu.backend.domain.trading.dto;

import com.modu.backend.domain.trading.entity.OrderModifyAction;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 미체결 주문 정정/취소 요청 DTO
 *
 * [action = MODIFY]
 * - newQuantity 또는 newPrice 중 하나 이상 필수
 * - KIS 호출 시 QTY_ALL_ORD_YN = "N" 고정
 *
 * [action = CANCEL]
 * - newQuantity, newPrice 불필요
 * - KIS 호출 시 QTY_ALL_ORD_YN = "Y" 고정 (전량 취소)
 */
public record ModifyOrderRequest(
        @NotNull OrderModifyAction action,
        @Min(1) Integer newQuantity,
        @Min(1) Long newPrice
) {
    @AssertTrue(message = "정정(MODIFY) 시 변경할 수량 또는 가격이 필요합니다.")
    public boolean isModifyHasChanges() {
        if (action != OrderModifyAction.MODIFY) return true;
        return newQuantity != null || newPrice != null;
    }
}
