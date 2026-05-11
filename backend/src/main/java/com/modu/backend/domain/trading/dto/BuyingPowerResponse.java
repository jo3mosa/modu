package com.modu.backend.domain.trading.dto;

/**
 * 주문 가능 금액/수량 조회 응답 DTO
 *
 * [데이터 출처]
 * - maxBuyAmount   : KIS inquire-psbl-order → nrcvb_buy_amt (미수 없는 최대 매수 가능 금액)
 * - maxSellQuantity: KIS inquire-psbl-sell  → ord_psbl_qty  (side=SELL 일 때만 조회, BUY 시 0)
 * - availableCash  : KIS inquire-psbl-order → ord_psbl_cash (미체결 주문 차감 후 주문 가능 현금)
 * - riskLimitAmount: DB trading_rules.daily_loss_limit_amount - 오늘 누적 매수 금액
 *                    (룰 미설정 또는 한도 초과 시 0)
 */
public record BuyingPowerResponse(
        Long maxBuyAmount,       // 최대 매수 가능 금액 (원)
        Integer maxBuyQuantity,  // 최대 매수 가능 수량 (side=BUY 시 제공, SELL 시 0)
        Integer maxSellQuantity, // 최대 매도 가능 수량 (side=SELL 시 제공, BUY 시 0)
        Long availableCash       // 현재 주문 가능 현금 (예수금 기반, 미체결 차감)
) {}
