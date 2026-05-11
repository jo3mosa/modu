package com.modu.backend.domain.trading.entity;

public enum OrderModifyAction {
    /** 정정 — 수량 또는 가격 변경 */
    MODIFY,
    /** 취소 — 미체결 잔량 전량 취소 */
    CANCEL
}
