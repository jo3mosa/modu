package com.modu.backend.domain.ai.controller;

import com.modu.backend.domain.ai.dto.AgentMessagePageResponse;
import com.modu.backend.domain.ai.service.AgentMessageService;
import com.modu.backend.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * AI 에이전트 회의 메시지 조회 API
 *
 * 실시간 메시지는 동일 사용자의 SSE 연결(/api/v1/orders/connect) 에 흐르는
 * "agent-message" 이벤트로 수신. 본 REST 는 채널 진입 시 과거 대화 로드 전용.
 */
@Tag(name = "AI Agent Messages", description = "AI 에이전트 회의 메시지 조회 API")
@RestController
@RequestMapping("/api/v1/ai-agent/messages")
@RequiredArgsConstructor
public class AgentMessageController {

    private final AgentMessageService agentMessageService;

    @Operation(
            summary = "에이전트 메시지 목록 조회 (복합 커서 페이지네이션)",
            description = """
                    종목 채널에 속한 AI 에이전트 발화 메시지를 (created_at, id) 내림차순으로 반환합니다.

                    [복합 커서]
                    - 첫 페이지: before, beforeId 둘 다 미전달.
                    - 다음 페이지: 응답의 nextCursor, nextCursorId 를 그대로 before, beforeId 로 전달.
                    - 둘은 항상 함께 전달해야 합니다 (하나만 전달 시 400).
                    - 응답의 nextCursor 가 null 이면 더 과거 데이터 없음.

                    [왜 복합 커서인가]
                    동일 created_at 을 가진 메시지가 페이지 경계에서 누락/중복되지 않도록 id 를 tie-breaker 로 묶습니다.

                    [size] 기본 50, 최대 100.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "stockCode 누락 / before·beforeId 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<AgentMessagePageResponse>> getMessages(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회할 종목 코드", example = "005930", required = true)
            @RequestParam String stockCode,
            @Parameter(description = "이 시각 이전 메시지를 조회. beforeId 와 함께 전달.", example = "2026-05-18T10:00:00+09:00")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime before,
            @Parameter(description = "복합 커서의 tie-breaker id. before 와 함께 전달.", example = "1234")
            @RequestParam(required = false) Long beforeId,
            @Parameter(description = "한 번에 조회할 메시지 수 (1~100, 기본 50)", example = "50")
            @RequestParam(required = false) Integer size
    ) {
        AgentMessagePageResponse response = agentMessageService.getMessages(userId, stockCode, before, beforeId, size);
        return ResponseEntity.ok(ApiResponse.success("에이전트 메시지를 조회했습니다.", response));
    }
}
