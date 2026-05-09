package com.modu.backend.domain.ai.controller;

import com.modu.backend.domain.ai.dto.AiJudgmentDetailResponse;
import com.modu.backend.domain.ai.dto.AiJudgmentPageResponse;
import com.modu.backend.domain.ai.service.AiJudgmentService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI Agent", description = "AI 판단 이력 API")
@RestController
@RequestMapping("/api/v1/ai-agent/decisions")
@RequiredArgsConstructor
public class AiJudgmentController {

    private final AiJudgmentService aiJudgmentService;

    @Operation(summary = "AI 판단 전체 이력 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "AI 판단 이력 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<AiJudgmentPageResponse>> getJudgments(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        AiJudgmentPageResponse response = aiJudgmentService.getJudgments(userId, page, size);
        return ResponseEntity.ok(ApiResponse.success("AI 판단 이력을 조회했습니다.", response));
    }

    @Operation(summary = "주문별 AI 판단 근거 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "주문별 AI 판단 근거 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "AI 판단 기록 없음")
    })
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<AiJudgmentDetailResponse>> getJudgmentByOrder(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long orderId) {

        AiJudgmentDetailResponse response = aiJudgmentService.getJudgmentByOrder(userId, orderId);
        return ResponseEntity.ok(ApiResponse.success("주문별 AI 판단 근거를 조회했습니다.", response));
    }
}
