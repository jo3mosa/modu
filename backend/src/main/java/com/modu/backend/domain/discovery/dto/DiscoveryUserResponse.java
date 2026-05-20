package com.modu.backend.domain.discovery.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 추천 응답 헤더의 사용자 등급 정보 — S14P31B106-362
 */
@Schema(description = "추천 응답 사용자 등급 정보")
public record DiscoveryUserResponse(
        @Schema(description = "사용자 risk_grade (T1~T5)", example = "T4")
        String riskGrade,

        @Schema(description = "사용자 risk_grade 라벨", example = "적극투자형")
        String riskLabel,

        @Schema(description = "InvestmentProfile.updatedAt — 등급 갱신 시각 (ISO 8601)",
                example = "2026-05-14T10:00:00+09:00", nullable = true)
        OffsetDateTime updatedAt
) {}
