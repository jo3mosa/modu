package com.modu.backend.domain.discovery.controller;

import com.modu.backend.domain.discovery.dto.DiscoveryResponse;
import com.modu.backend.domain.discovery.service.DiscoveryService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 종목 추천 (Discovery) API — S14P31B106-362
 *
 * 사용자 risk_grade 이하 tier 의 종목을 큐레이션해 응답.
 * FE DiscoveryPage 와 매칭 — mock 응답 schema 그대로.
 *
 * 데이터 흐름:
 *   DA 가 daily_fundamentals.risk_tier / roe_rank_pct / volume_spike 를 적재한 뒤
 *   BE 가 stock_master + 3 테이블 LEFT JOIN 으로 tier 별 상위 N개 추출.
 *   tag / filter / reason 은 status 컬럼 직역 매핑 (자유 큐레이션 텍스트는 후속).
 */
@Tag(name = "Discovery", description = "종목 추천 (Discovery) API")
@Validated
@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class DiscoveryController {

    private static final int DEFAULT_PER_TIER = 5;

    private final DiscoveryService discoveryService;

    @Operation(
            summary = "종목 추천 (등급별 큐레이션)",
            description = """
                    사용자의 risk_grade 이하 tier 에 해당하는 종목을 등급 (T1~T5) 별로 큐레이션해 반환합니다.

                    - 사용자 등급 초과 tier 는 unlocked=false 로 표시되며 stocks 는 빈 배열.
                    - tier 내 정렬: roe_rank_pct 오름차순 (수익성 상위 우선), NULL 은 후순위.
                    - perTier 미만의 종목만 매칭되는 경우 매칭 건수만큼 반환 (빈 응답 가능).
                    - 데이터 의존: DA 의 compute_risk_tier / compute_fundamental_ranks /
                      compute_volume_spike 적재 결과.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "perTier 범위 오류 (1~50)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "사용자 투자성향 프로필 미설정")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<DiscoveryResponse>> getRecommendations(
            @AuthenticationPrincipal Long userId,

            @Parameter(description = "tier 당 최대 종목 수 (1~50, 기본 5)", example = "5")
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PER_TIER)
            @Min(1) @Max(50) int perTier
    ) {
        DiscoveryResponse response = discoveryService.getRecommendations(userId, perTier);
        return ResponseEntity.ok(ApiResponse.success("종목 추천을 성공적으로 조회했습니다.", response));
    }
}
