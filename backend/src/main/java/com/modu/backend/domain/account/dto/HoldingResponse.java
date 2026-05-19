package com.modu.backend.domain.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "보유 종목 정보")
public record HoldingResponse(
        @Schema(description = "종목 코드", example = "005930")
        String stockCode,

        @Schema(description = "종목명", example = "삼성전자")
        String stockName,

        @Schema(description = "보유 수량", example = "10")
        Long quantity,

        @Schema(description = "매입 평균가 (분할 매수 시 소수점 가능)", example = "2436.60")
        Double avgBuyPrice,

        @Schema(description = "현재가", example = "80000")
        Long currentPrice,

        @Schema(description = "평가 손익 금액", example = "50000")
        Long pnl,

        @Schema(description = "평가 손익율 (%)", example = "6.67")
        Double pnlPct
) {}
