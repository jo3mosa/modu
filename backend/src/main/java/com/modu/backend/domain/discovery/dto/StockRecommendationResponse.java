package com.modu.backend.domain.discovery.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 추천 종목 응답 — S14P31B106-362
 *
 * tags / filters / reason 는 BE 측 status 직역 매핑 + 정적 템플릿 (자동 분류).
 * AI / DA 의 자유 큐레이션 텍스트는 후속 task 영역.
 */
@Schema(description = "추천 종목 항목")
public record StockRecommendationResponse(
        @Schema(description = "종목 코드", example = "005930")
        String stockCode,

        @Schema(description = "종목명", example = "삼성전자")
        String stockName,

        @Schema(description = "시장 (KOSPI / KOSDAQ)", example = "KOSPI")
        String market,

        @Schema(description = "섹터 / 업종", example = "IT", nullable = true)
        String sector,

        @Schema(description = "현재가 (daily_ohlcv 최신일 close)", example = "76800")
        Integer price,

        @Schema(description = "전일 대비 등락률 (%)", example = "1.85", nullable = true)
        Double changePct,

        @Schema(description = "추천 사유 (status 기반 정적 템플릿)", example = "수익성 우수 + 안정성 양호")
        String reason,

        @Schema(description = "특징 태그 (status 직역)", example = "[\"안정성 우수\", \"고수익\"]")
        List<String> tags,

        @Schema(description = "핵심 지표")
        StockMetricsResponse metrics,

        @Schema(description = "filter chip 카테고리 매칭 (모멘텀 / 가치 / 이슈)",
                example = "[\"모멘텀\", \"이슈\"]")
        List<String> filters,

        @Schema(description = "데이터 기준 시각 (daily_ohlcv 최신일 KST). ISO 8601",
                example = "2026-05-20T15:30:00+09:00")
        OffsetDateTime updatedAt
) {}
