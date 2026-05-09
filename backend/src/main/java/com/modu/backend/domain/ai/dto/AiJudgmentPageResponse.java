package com.modu.backend.domain.ai.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record AiJudgmentPageResponse(
        List<AiJudgmentSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
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
