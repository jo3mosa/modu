package com.modu.backend.domain.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 에이전트 메시지 커서 페이지네이션 응답
 *
 * [정렬] content 는 created_at DESC.
 * [커서] nextCursor 는 마지막 항목의 createdAt. 다음 호출 시 ?before=nextCursor 로 전달.
 * [hasMore] 더 과거 데이터가 있는지 — 프론트가 위로 스크롤 버튼/자동 로딩 결정.
 */
@Schema(description = "AI 에이전트 메시지 목록 응답 (커서 페이지네이션)")
public record AgentMessagePageResponse(
        @Schema(description = "메시지 목록 (created_at DESC)")
        List<AgentMessageResponse> content,

        @Schema(description = "다음 페이지 요청 시 사용할 커서. null 이면 마지막 페이지.", nullable = true)
        OffsetDateTime nextCursor,

        @Schema(description = "더 과거 데이터 존재 여부", example = "true")
        boolean hasMore
) {
}
