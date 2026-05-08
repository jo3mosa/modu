package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.auth.dto.OnboardingStatus;
import com.modu.backend.domain.investment.entity.InvestmentProfile;
import com.modu.backend.domain.investment.entity.ProfileHistory;
import com.modu.backend.domain.investment.exception.InvestmentErrorCode;
import com.modu.backend.domain.investment.repository.InvestmentProfileRepository;
import com.modu.backend.domain.investment.repository.ProfileHistoryRepository;
import com.modu.backend.domain.strategy.dto.InvestmentRiskLevel;
import com.modu.backend.domain.strategy.dto.ProfileAnswerResponse;
import com.modu.backend.domain.strategy.dto.ProfileResponse;
import com.modu.backend.domain.strategy.dto.ProfileUpdateRequest;
import com.modu.backend.domain.strategy.dto.ProfileUpdateResponse;
import com.modu.backend.domain.trading.repository.TradingRuleRepository;
import com.modu.backend.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StrategyProfileService {

    private final StrategyProfileQuestionService strategyProfileQuestionService;
    private final InvestmentProfileRepository investmentProfileRepository;
    private final ProfileHistoryRepository profileHistoryRepository;
    private final TradingRuleRepository tradingRuleRepository;

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(Long userId) {
        InvestmentProfile profile = investmentProfileRepository.findById(userId)
                .orElseThrow(() -> new ApiException(InvestmentErrorCode.PROFILE_NOT_FOUND));

        Map<String, Object> answersSnapshot = profile.getAnswersSnapshot();

        return new ProfileResponse(
                InvestmentRiskLevel.valueOf(profile.getRiskGrade()),
                profile.getProfileSummary(),
                toAnswerResponses(answersSnapshot),
                toNullableString(answersSnapshot.get("freeText")),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }

    @Transactional
    public ProfileUpdateResponse updateProfile(Long userId, ProfileUpdateRequest request) {
        StrategyProfileQuestionService.ProfileAssessment assessment =
                strategyProfileQuestionService.assess(request.answers());

        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> answersSnapshot = toAnswersSnapshot(assessment, request.freeText());

        InvestmentProfile profile = investmentProfileRepository.findById(userId)
                .map(existing -> updateExistingProfile(existing, assessment, answersSnapshot, now))
                .orElseGet(() -> createProfile(userId, assessment, answersSnapshot, now));

        investmentProfileRepository.saveAndFlush(profile);
        profileHistoryRepository.save(toHistory(profile, now));

        return new ProfileUpdateResponse(
                assessment.riskLevel(),
                assessment.riskScore(),
                assessment.profileSummary(),
                profile.getUpdatedAt(),
                new OnboardingStatus(true, tradingRuleRepository.existsByUserId(userId))
        );
    }

    private InvestmentProfile updateExistingProfile(
            InvestmentProfile profile,
            StrategyProfileQuestionService.ProfileAssessment assessment,
            Map<String, Object> answersSnapshot,
            OffsetDateTime now
    ) {
        profile.update(
                assessment.riskScore(),
                assessment.riskLevel().name(),
                assessment.profileSummary(),
                assessment.investmentGoal(),
                answersSnapshot,
                now
        );
        return profile;
    }

    private InvestmentProfile createProfile(
            Long userId,
            StrategyProfileQuestionService.ProfileAssessment assessment,
            Map<String, Object> answersSnapshot,
            OffsetDateTime now
    ) {
        return InvestmentProfile.builder()
                .userId(userId)
                .riskScore(assessment.riskScore())
                .riskGrade(assessment.riskLevel().name())
                .profileSummary(assessment.profileSummary())
                .investmentGoal(assessment.investmentGoal())
                .answersSnapshot(answersSnapshot)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private ProfileHistory toHistory(InvestmentProfile profile, OffsetDateTime now) {
        return ProfileHistory.builder()
                .userId(profile.getUserId())
                .riskScore(profile.getRiskScore())
                .riskGrade(profile.getRiskGrade())
                .investmentGoal(profile.getInvestmentGoal())
                .answersSnapshot(profile.getAnswersSnapshot())
                .versionNo(profile.getVersion())
                .createdAt(now)
                .build();
    }

    private Map<String, Object> toAnswersSnapshot(
            StrategyProfileQuestionService.ProfileAssessment assessment,
            String freeText
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("answers", assessment.answers());
        snapshot.put("freeText", freeText);
        snapshot.put("riskScore", assessment.riskScore());
        snapshot.put("riskLevel", assessment.riskLevel().name());
        return snapshot;
    }

    private List<ProfileAnswerResponse> toAnswerResponses(Map<String, Object> answersSnapshot) {
        Object answers = answersSnapshot.get("answers");
        if (!(answers instanceof List<?> answerList)) {
            throw new IllegalStateException("Investment profile answers snapshot is invalid.");
        }

        return answerList.stream()
                .map(this::toAnswerResponse)
                .toList();
    }

    private ProfileAnswerResponse toAnswerResponse(Object answer) {
        if (answer instanceof StrategyProfileQuestionService.AnswerSnapshot snapshot) {
            return new ProfileAnswerResponse(snapshot.questionId(), snapshot.question(), snapshot.answer());
        }

        if (answer instanceof Map<?, ?> snapshot) {
            return new ProfileAnswerResponse(
                    requiredString(snapshot, "questionId"),
                    requiredString(snapshot, "question"),
                    requiredString(snapshot, "answer")
            );
        }

        throw new IllegalStateException("Investment profile answer snapshot is invalid.");
    }

    private String requiredString(Map<?, ?> snapshot, String key) {
        Object value = snapshot.get(key);
        if (value instanceof String stringValue) {
            return stringValue;
        }
        throw new IllegalStateException("Investment profile answer snapshot is invalid.");
    }

    private String toNullableString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }
        throw new IllegalStateException("Investment profile freeText snapshot is invalid.");
    }
}
