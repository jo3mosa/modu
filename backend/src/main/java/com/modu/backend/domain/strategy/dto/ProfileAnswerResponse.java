package com.modu.backend.domain.strategy.dto;

public record ProfileAnswerResponse(
        String questionId,
        String question,
        String answer
) {
}
