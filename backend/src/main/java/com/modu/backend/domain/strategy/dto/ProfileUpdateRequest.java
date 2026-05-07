package com.modu.backend.domain.strategy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ProfileUpdateRequest(
        @NotEmpty List<@Valid ProfileAnswerRequest> answers,
        String freeText
) {
}
