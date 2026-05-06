package com.modu.backend.domain.market.dto;

import java.util.List;

/**
 * 실시간 호가 응답 DTO
 *
 * KIS H0STASP0 평면 payload를 매도/매수 10단계 배열 구조로 변환
 */
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

    /**
     * 호가 단계별 가격/잔량
     */
    public record OrderbookLevel(
            int level,
            Long price,
            Long quantity
    ) {
    }
}
