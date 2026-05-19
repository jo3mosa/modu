package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.auth.dto.OnboardingStatus;
import com.modu.backend.domain.investment.entity.InvestmentProfile;
import com.modu.backend.domain.investment.entity.ProfileHistory;
import com.modu.backend.domain.investment.event.UsersByGradeChangedEvent;
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
import com.modu.backend.global.error.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StrategyProfileService {

    private static final String POSTGRES_DUPLICATE_KEY_SQL_STATE = "23505";

    private final StrategyProfileQuestionService strategyProfileQuestionService;
    private final InvestmentProfileRepository investmentProfileRepository;
    private final ProfileHistoryRepository profileHistoryRepository;
    private final TradingRuleRepository tradingRuleRepository;
    private final ApplicationEventPublisher eventPublisher;

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
                profile.getUpdatedAt(),
                profile.getVersion()
        );
    }

    @Transactional
    public ProfileUpdateResponse updateProfile(Long userId, ProfileUpdateRequest request) {
        StrategyProfileQuestionService.ProfileAssessment assessment =
                strategyProfileQuestionService.assess(request.answers());

        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> answersSnapshot = toAnswersSnapshot(assessment, request.freeText());

        InvestmentProfile profile;
        Long historyVersionNo;
        Integer prevGradeInt;

        var existingProfile = investmentProfileRepository.findById(userId);
        if (existingProfile.isPresent()) {
            profile = existingProfile.get();
            prevGradeInt = toGradeInt(profile.getRiskGrade());
            historyVersionNo = nextVersion(profile);
            updateExistingProfile(profile, request.version(), assessment, answersSnapshot, now);
        } else {
            profile = createNewProfile(userId, assessment, answersSnapshot, now);
            historyVersionNo = 0L;
            prevGradeInt = null;
        }

        profileHistoryRepository.save(toHistory(profile, historyVersionNo, now));

        eventPublisher.publishEvent(
                new UsersByGradeChangedEvent(userId, prevGradeInt, assessment.riskLevel().toGradeInt())
        );

        return new ProfileUpdateResponse(
                assessment.riskLevel(),
                assessment.riskScore(),
                assessment.profileSummary(),
                profile.getUpdatedAt(),
                historyVersionNo,
                new OnboardingStatus(true, tradingRuleRepository.existsByUserId(userId))
        );
    }

    private void updateExistingProfile(
            InvestmentProfile profile,
            Long requestVersion,
            StrategyProfileQuestionService.ProfileAssessment assessment,
            Map<String, Object> answersSnapshot,
            OffsetDateTime now
    ) {
        validateVersion(profile, requestVersion);
        profile.update(
                assessment.riskScore(),
                assessment.riskLevel().name(),
                assessment.profileSummary(),
                assessment.investmentGoal(),
                answersSnapshot,
                now
        );
    }

    private void validateVersion(InvestmentProfile profile, Long requestVersion) {
        if (requestVersion == null) {
            throw new ValidationException("투자 성향 프로필 version은 필수입니다.");
        }
        if (!requestVersion.equals(profile.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(InvestmentProfile.class, profile.getUserId());
        }
    }

    private Integer toGradeInt(String riskGrade) {
        if (riskGrade == null) return null;
        try {
            return InvestmentRiskLevel.valueOf(riskGrade).toGradeInt();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Long nextVersion(InvestmentProfile profile) {
        return profile.getVersion() == null ? 0L : profile.getVersion() + 1;
    }

    private InvestmentProfile createNewProfile(
            Long userId,
            StrategyProfileQuestionService.ProfileAssessment assessment,
            Map<String, Object> answersSnapshot,
            OffsetDateTime now
    ) {
        try {
            return investmentProfileRepository.saveAndFlush(createProfile(userId, assessment, answersSnapshot, now));
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateKeyViolation(e)) {
                throw e;
            }
            throw new ObjectOptimisticLockingFailureException(InvestmentProfile.class, userId);
        }
    }

    private boolean isDuplicateKeyViolation(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }

        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && POSTGRES_DUPLICATE_KEY_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }

        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase().contains("duplicate key");
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

    private ProfileHistory toHistory(InvestmentProfile profile, Long versionNo, OffsetDateTime now) {
        return ProfileHistory.builder()
                .userId(profile.getUserId())
                .riskScore(profile.getRiskScore())
                .riskGrade(profile.getRiskGrade())
                .investmentGoal(profile.getInvestmentGoal())
                .answersSnapshot(profile.getAnswersSnapshot())
                .versionNo(versionNo)
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
