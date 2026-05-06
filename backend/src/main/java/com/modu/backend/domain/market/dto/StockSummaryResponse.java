package com.modu.backend.domain.market.dto;

import com.modu.backend.domain.market.entity.StockMaster;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "개별 종목 요약 정보")
public record StockSummaryResponse(
        @Schema(description = "종목코드", example = "005930")
        String stockCode,

        @Schema(description = "종목명", example = "삼성전자")
        String stockName,

        @Schema(description = "시장 구분 (KOSPI / KOSDAQ)", example = "KOSPI")
        String marketType
) {
    public static StockSummaryResponse from(StockMaster stock) {
        return new StockSummaryResponse(
                stock.getStockCode(),
                stock.getStockName(),
                stock.getMarketType()
        );
    }
}
