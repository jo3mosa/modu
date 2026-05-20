package com.modu.backend.domain.discovery.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 종목 추천 (Discovery) 전체 응답 — S14P31B106-362
 *
 * GET /api/v1/recommendations
 *
 * FE DiscoveryPage 의 mock 구조 매칭. 사용자 등급 이하 tier 만 stocks 채워짐.
 * 초과 tier 는 unlocked=false + stocks=[].
 */
@Schema(description = "종목 추천 전체 응답")
public record DiscoveryResponse(
        @Schema(description = "사용자 등급 정보")
        DiscoveryUserResponse user,

        @Schema(description = "추천 큐레이션 기준 시각 (응답 생성 시각, ISO 8601)",
                example = "2026-05-20T15:30:00+09:00")
        OffsetDateTime recommendedAt,

        @Schema(description = "응답에 포함된 전체 추천 종목 수", example = "12")
        int totalCount,

        @Schema(description = "tier 별 종목 수 ({\"T1\":3, \"T2\":2, ...})",
                example = "{\"T1\":3,\"T2\":2,\"T3\":3,\"T4\":2,\"T5\":0}")
        Map<String, Integer> tierCounts,

        @Schema(description = "tier 별 블록 목록 (T1~T5 순서)")
        List<TierBlockResponse> tiers
) {}
