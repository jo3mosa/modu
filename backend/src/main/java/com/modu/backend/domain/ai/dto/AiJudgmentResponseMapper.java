package com.modu.backend.domain.ai.dto;

import com.modu.backend.domain.ai.entity.AiJudgment;

import java.util.Locale;
import java.util.Set;

final class AiJudgmentResponseMapper {

    private static final Set<String> ALLOWED_EVENT_TYPES = Set.of(
            "PASSED",
            "HOLD",
            "BLOCKED",
            "APPROVAL_REQUIRED"
    );

    private AiJudgmentResponseMapper() {
    }

    static ValidatedJudgment validate(AiJudgment judgment) {
        return new ValidatedJudgment(
                validateEventType(judgment.getDecision()),
                validateConfidenceScore(judgment.getConfidenceScore())
        );
    }

    private static String validateEventType(String decision) {
        if (decision == null || decision.isBlank()) {
            throw new IllegalStateException("AI judgment decision must not be blank.");
        }

        String eventType = decision.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_EVENT_TYPES.contains(eventType)) {
            throw new IllegalStateException("Unsupported AI judgment decision: " + decision);
        }

        return eventType;
    }

    private static Long validateConfidenceScore(Long confidenceScore) {
        if (confidenceScore == null || confidenceScore < 0 || confidenceScore > 100) {
            throw new IllegalStateException("AI judgment confidenceScore must be between 0 and 100.");
        }

        return confidenceScore;
    }

    record ValidatedJudgment(String eventType, Long confidenceScore) {
    }
}
