package com.modu.backend.domain.market.dto;

import java.util.List;

public record OrderbookResponse(
        String stockCode,
        String businessTime,
        List<OrderbookLevel> asks,
        List<OrderbookLevel> bids,
        Long totalAskQuantity,
        Long totalBidQuantity,
        Long expectedPrice,
        Long expectedQuantity,
        Long accumulatedVolume
) {

    public record OrderbookLevel(
            int level,
            Long price,
            Long quantity
    ) {
    }
}

