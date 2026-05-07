package com.modu.backend.domain.strategy.dto;

import java.util.List;

public record ProfileQuestionListResponse(
        List<ProfileQuestionResponse> questions
) {
}
