package com.modu.backend.domain.strategy.dto;

import java.util.List;

public record ProfileQuestionResponse(
        String questionId,
        String question,
        ProfileQuestionType type,
        boolean required,
        ProfileQuestionScoringType scoringType,
        List<ProfileQuestionOptionResponse> options
) {
}
