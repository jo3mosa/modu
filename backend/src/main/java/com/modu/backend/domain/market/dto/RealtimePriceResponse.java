package com.modu.backend.domain.market.dto;

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

