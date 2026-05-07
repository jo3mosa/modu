package com.modu.backend.domain.strategy.dto;

import jakarta.validation.constraints.NotBlank;

public record ProfileAnswerRequest(
        @NotBlank String questionId,
        @NotBlank String optionId
) {
}
