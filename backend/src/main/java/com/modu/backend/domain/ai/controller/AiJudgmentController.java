package com.modu.backend.domain.ai.controller;

import com.modu.backend.domain.ai.dto.AiJudgmentDetailResponse;
import com.modu.backend.domain.ai.dto.AiJudgmentPageResponse;
import com.modu.backend.domain.ai.dto.DecisionApprovalResponse;
import com.modu.backend.domain.ai.dto.PendingDecisionResponse;
import com.modu.backend.domain.ai.service.AiJudgmentService;
import com.modu.backend.domain.ai.service.PendingJudgmentService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "AI Agent", description = "AI 판단 이력 조회 API")
@RestController
@RequestMapping("/api/v1/ai-agent/decisions")
@RequiredArgsConstructor
public class AiJudgmentController {

    private final AiJudgmentService aiJudgmentService;
    private final PendingJudgmentService pendingJudgmentService;

    @Operation(
            summary = "AI 판단 전체 이력 조회",
            description = """
                    인증된 사용자의 AI 판단 이력을 최신 판단 일시 기준으로 페이지 조회합니다.

                    - eventType은 DB의 ai_judgments.decision 값을 API 의미에 맞게 내려주는 필드입니다.
                    - 허용 값은 PASSED, HOLD, BLOCKED, APPROVAL_REQUIRED입니다.
                    - 목록 응답은 카드/리스트 표시용 요약 정보만 포함합니다.
                    - 판단 시점의 지표 스냅샷은 주문별 상세 조회 API에서 확인합니다.
                    - 조회 결과가 없으면 빈 목록과 페이지 메타데이터를 200 OK로 반환합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "AI 판단 이력 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<AiJudgmentPageResponse>> getJudgments(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회할 페이지 번호입니다. 0부터 시작합니다.", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "한 페이지에 조회할 판단 이력 수입니다. 1 이상 100 이하만 허용합니다.", example = "20")
            @RequestParam(defaultValue = "20") int size) {

        AiJudgmentPageResponse response = aiJudgmentService.getJudgments(userId, page, size);
        return ResponseEntity.ok(ApiResponse.success("AI 판단 이력을 조회했습니다.", response));
    }

    @Operation(
            summary = "주문별 AI 판단 근거 조회",
            description = """
                    인증된 사용자의 특정 주문에 연결된 AI 판단 근거를 조회합니다.

                    - orderId는 주문 ID이며 path variable로 전달합니다.
                    - 다른 사용자의 주문 또는 AI 판단 이력은 조회할 수 없습니다.
                    - 주문이 존재하더라도 연결된 AI 판단 기록이 없으면 AI_001 404 응답을 반환합니다.
                    - 상세 응답에는 판단 당시 저장된 indicators_snapshot JSONB 값을 indicatorsSnapshot 객체로 포함합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "주문별 AI 판단 근거 조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "AI 판단 기록 없음")
    })
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<AiJudgmentDetailResponse>> getJudgmentByOrder(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "AI 판단 근거를 조회할 주문 ID입니다.", example = "5001", required = true)
            @PathVariable Long orderId) {

        AiJudgmentDetailResponse response = aiJudgmentService.getJudgmentByOrder(userId, orderId);
        return ResponseEntity.ok(ApiResponse.success("주문별 AI 판단 근거를 조회했습니다.", response));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 승인 대기 / 승인 / 거부 (S14P31B106-292)
    // ──────────────────────────────────────────────────────────────────────

    @Operation(
            summary = "승인 대기 AI 판단 목록",
            description = """
                    execution_status = APPROVAL_REQUIRED 이고 만료되지 않은 판단을 최신순으로 반환합니다.
                    APPROVAL_REQUIRED 진입 시점에 5분 만료 시각이 설정되며, 만료된 판단은 스케줄러가 EXPIRED 로 전환합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<PendingDecisionResponse>>> listPending(
            @AuthenticationPrincipal Long userId) {
        List<PendingDecisionResponse> pending = pendingJudgmentService.listPending(userId);
        return ResponseEntity.ok(ApiResponse.success("승인 대기 목록을 조회했습니다.", pending));
    }

    @Operation(
            summary = "AI 판단 승인",
            description = """
                    APPROVAL_REQUIRED 상태인 AI 판단을 승인하여 LIMIT 주문 발행 흐름으로 전환합니다.

                    [흐름]
                      1. execution_status → READY, order_id 연결
                      2. orders 신규 row INSERT (PENDING / LIMIT / AI_DECISION)
                      3. BUY 시 PositionThreshold.ai_*_price 갱신 (활성 row 있을 때만)
                      4. trade.order.submitted Kafka 발행 (커밋 후)

                    [실패]
                      - 본인 판단 아님 → DECISION_FORBIDDEN
                      - 이미 처리됨 → DECISION_NOT_PENDING
                      - 만료됨 → DECISION_EXPIRED
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "승인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 판단 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "AI 판단 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 처리됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "410", description = "만료됨")
    })
    @PostMapping("/{judgmentId}/approve")
    public ResponseEntity<ApiResponse<DecisionApprovalResponse>> approve(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long judgmentId) {
        DecisionApprovalResponse response = pendingJudgmentService.approve(userId, judgmentId);
        return ResponseEntity.ok(ApiResponse.success("AI 판단을 승인했습니다.", response));
    }

    @Operation(
            summary = "AI 판단 거부",
            description = "APPROVAL_REQUIRED 상태인 AI 판단을 거부합니다. execution_status → REJECTED."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "거부 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 판단 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "AI 판단 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 처리됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "410", description = "만료됨")
    })
    @PostMapping("/{judgmentId}/reject")
    public ResponseEntity<ApiResponse<DecisionApprovalResponse>> reject(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long judgmentId) {
        DecisionApprovalResponse response = pendingJudgmentService.reject(userId, judgmentId);
        return ResponseEntity.ok(ApiResponse.success("AI 판단을 거부했습니다.", response));
    }
}
