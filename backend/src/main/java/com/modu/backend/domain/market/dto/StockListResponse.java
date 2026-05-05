package com.modu.backend.domain.market.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "종목 목록 조회 응답")
public record StockListResponse(
        @Schema(description = "종목 목록")
        List<StockSummaryResponse> stocks,

        @Schema(description = "검색된 전체 데이터 건수", example = "2500")
        long totalCount,

        @Schema(description = "현재 페이지 번호 (1부터 시작)", example = "1")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size
) {}
