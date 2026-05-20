package com.modu.backend.domain.discovery.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * tier 별 추천 블록 — S14P31B106-362
 *
 * unlocked = false 인 tier 도 응답에 포함 (FE 가 잠금 UI 표시).
 * 사용자 등급 초과 tier 는 stocks 빈 배열.
 */
@Schema(description = "tier 별 추천 블록")
public record TierBlockResponse(
        @Schema(description = "tier 키 (T1~T5)", example = "T1")
        String tier,

        @Schema(description = "tier 라벨", example = "안정형")
        String label,

        @Schema(description = "tier 설명", example = "저변동성 KOSPI 대형주. 안정성·수익성 중심.")
        String description,

        @Schema(description = "사용자 등급으로 해금됐는지 여부", example = "true")
        boolean unlocked,

        @Schema(description = "tier 에 속한 추천 종목 목록 (해금 안 됐거나 매칭 종목 없으면 빈 배열)")
        List<StockRecommendationResponse> stocks
) {}
