package com.modu.backend.domain.strategy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProfileUpdateRequest(
        @NotEmpty @Size(min = 9, max = 9) List<@Valid ProfileAnswerRequest> answers,
        String freeText,
        Long version
) {
}
