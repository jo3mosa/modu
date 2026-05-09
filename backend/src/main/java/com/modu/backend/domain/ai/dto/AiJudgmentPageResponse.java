package com.modu.backend.domain.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "AI 판단 이력 페이지 응답")
public record AiJudgmentPageResponse(
        @Schema(description = "AI 판단 이력 목록")
        List<AiJudgmentSummaryResponse> content,

        @Schema(description = "현재 페이지 번호. 0부터 시작합니다.", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "전체 판단 이력 수", example = "42")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "3")
        int totalPages,

        @Schema(description = "다음 페이지 존재 여부", example = "true")
        boolean hasNext
) {

    public static AiJudgmentPageResponse from(Page<AiJudgmentSummaryResponse> page) {
        return new AiJudgmentPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }
}
