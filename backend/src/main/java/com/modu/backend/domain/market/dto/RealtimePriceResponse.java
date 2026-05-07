package com.modu.backend.domain.market.dto;

/**
 * 실시간 체결가 응답 DTO
 *
 * KIS H0STCNT0 payload 중 프론트 화면 표시용 핵심 필드 매핑
 */
public record RealtimePriceResponse(
        String stockCode,
        String tradeTime,
        Long currentPrice,
        String priceChangeSign,
        Long priceChange,
        Double priceChangeRate,
        Long openPrice,
        Long highPrice,
        Long lowPrice,
        Long askPrice,
        Long bidPrice,
        Long tradeVolume,
        Long accumulatedVolume,
        Long accumulatedTradeAmount,
        Double tradeStrength,
        Long totalAskQuantity,
        Long totalBidQuantity,
        Long viStandardPrice
) {
}
