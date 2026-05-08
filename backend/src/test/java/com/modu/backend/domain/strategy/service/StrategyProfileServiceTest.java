package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.investment.entity.InvestmentProfile;
import com.modu.backend.domain.investment.entity.ProfileHistory;
import com.modu.backend.domain.investment.exception.InvestmentErrorCode;
import com.modu.backend.domain.investment.repository.InvestmentProfileRepository;
import com.modu.backend.domain.investment.repository.ProfileHistoryRepository;
import com.modu.backend.domain.strategy.dto.InvestmentRiskLevel;
import com.modu.backend.domain.strategy.dto.ProfileAnswerRequest;
import com.modu.backend.domain.strategy.dto.ProfileResponse;
import com.modu.backend.domain.strategy.dto.ProfileUpdateRequest;
import com.modu.backend.domain.strategy.dto.ProfileUpdateResponse;
import com.modu.backend.domain.trading.repository.TradingRuleRepository;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyProfileServiceTest {

    @Mock InvestmentProfileRepository investmentProfileRepository;
    @Mock ProfileHistoryRepository profileHistoryRepository;
    @Mock TradingRuleRepository tradingRuleRepository;

    private final StrategyProfileQuestionService strategyProfileQuestionService =
            new StrategyProfileQuestionService();

    StrategyProfileService strategyProfileService;

    @BeforeEach
    void setUp() {
        strategyProfileService = new StrategyProfileService(
                strategyProfileQuestionService,
                investmentProfileRepository,
                profileHistoryRepository,
                tradingRuleRepository
        );
    }

    @Test
    @DisplayName("투자 성향 프로필을 조회하면 저장된 스냅샷을 응답으로 반환한다")
    void getProfile() {
        // given
        Long userId = 1L;
        OffsetDateTime createdAt = OffsetDateTime.now().minusDays(1);
        OffsetDateTime updatedAt = OffsetDateTime.now();
        InvestmentProfile existingProfile = InvestmentProfile.builder()
                .userId(userId)
                .riskScore(68L)
                .riskGrade("ACTIVE")
                .profileSummary("active profile summary")
                .investmentGoal("market average return")
                .answersSnapshot(Map.of(
                        "answers", List.of(
                                Map.of(
                                        "questionId", "AGE_GROUP",
                                        "question", "age group question",
                                        "optionId", "AGE_20_TO_40",
                                        "answer", "20 to 40",
                                        "scoringType", "SCORED"
                                ),
                                Map.of(
                                        "questionId", "INVESTMENT_PERIOD",
                                        "question", "investment period question",
                                        "optionId", "OVER_3_YEARS",
                                        "answer", "over 3 years",
                                        "scoringType", "SCORED"
                                )
                        ),
                        "freeText", "prefer dividend stocks",
                        "riskScore", 68L,
                        "riskLevel", "ACTIVE"
                ))
                .version(1L)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();

        when(investmentProfileRepository.findById(userId)).thenReturn(Optional.of(existingProfile));

        // when
        ProfileResponse response = strategyProfileService.getProfile(userId);

        // then
        assertThat(response.riskLevel()).isEqualTo(InvestmentRiskLevel.ACTIVE);
        assertThat(response.profileSummary()).isEqualTo("active profile summary");
        assertThat(response.freeText()).isEqualTo("prefer dividend stocks");
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
        assertThat(response.answers()).hasSize(2);
        assertThat(response.answers().get(0).questionId()).isEqualTo("AGE_GROUP");
        assertThat(response.answers().get(0).answer()).isEqualTo("20 to 40");
    }

    @Test
    @DisplayName("투자 성향 프로필이 없으면 PROFILE_NOT_FOUND 예외를 던진다")
    void getProfileNotFound() {
        // given
        Long userId = 1L;
        when(investmentProfileRepository.findById(userId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> strategyProfileService.getProfile(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(InvestmentErrorCode.PROFILE_NOT_FOUND));
    }

    @Test
    @DisplayName("신규 투자 성향 프로필을 저장하고 온보딩 설문 완료 상태를 반환한다")
    void createProfile() {
        // given
        Long userId = 1L;
        when(investmentProfileRepository.findById(userId)).thenReturn(Optional.empty());
        when(investmentProfileRepository.saveAndFlush(any(InvestmentProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tradingRuleRepository.existsByUserId(userId)).thenReturn(false);

        // when
        ProfileUpdateResponse response =
                strategyProfileService.updateProfile(userId, requestForActiveProfile());

        // then
        assertThat(response.riskLevel()).isEqualTo(InvestmentRiskLevel.ACTIVE);
        assertThat(response.riskScore()).isEqualTo(68L);
        assertThat(response.onboarding().isSurveyCompleted()).isTrue();
        assertThat(response.onboarding().isRuleSetCompleted()).isFalse();

        ArgumentCaptor<InvestmentProfile> profileCaptor =
                ArgumentCaptor.forClass(InvestmentProfile.class);
        verify(investmentProfileRepository).saveAndFlush(profileCaptor.capture());

        InvestmentProfile savedProfile = profileCaptor.getValue();
        assertThat(savedProfile.getUserId()).isEqualTo(userId);
        assertThat(savedProfile.getRiskScore()).isEqualTo(68L);
        assertThat(savedProfile.getRiskGrade()).isEqualTo("ACTIVE");
        assertThat(savedProfile.getVersion()).isNull();
        assertThat(savedProfile.getInvestmentGoal()).isEqualTo("시장 평균 이상의 수익을 기대해요");
        assertThat(savedProfile.getAnswersSnapshot())
                .containsEntry("riskScore", 68L)
                .containsEntry("riskLevel", "ACTIVE")
                .containsEntry("freeText", "배당주 위주로 안정적으로 운용하고 싶습니다.");

        verify(profileHistoryRepository).save(any(ProfileHistory.class));
    }

    @Test
    @DisplayName("기존 투자 성향 프로필을 수정하면 버전을 증가시키고 이력을 저장한다")
    void updateProfile() {
        // given
        Long userId = 1L;
        OffsetDateTime createdAt = OffsetDateTime.now().minusDays(1);
        InvestmentProfile existingProfile = InvestmentProfile.builder()
                .userId(userId)
                .riskScore(30L)
                .riskGrade("STABLE_SEEKING")
                .profileSummary("기존 요약")
                .investmentGoal("기존 목표")
                .answersSnapshot(Map.of("riskScore", 30L))
                .version(1L)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();

        when(investmentProfileRepository.findById(userId)).thenReturn(Optional.of(existingProfile));
        when(tradingRuleRepository.existsByUserId(userId)).thenReturn(true);

        // when
        ProfileUpdateResponse response =
                strategyProfileService.updateProfile(userId, requestForActiveProfile(1L));

        // then
        assertThat(response.riskLevel()).isEqualTo(InvestmentRiskLevel.ACTIVE);
        assertThat(response.onboarding().isSurveyCompleted()).isTrue();
        assertThat(response.onboarding().isRuleSetCompleted()).isTrue();
        assertThat(existingProfile.getRiskScore()).isEqualTo(68L);
        assertThat(existingProfile.getRiskGrade()).isEqualTo("ACTIVE");
        assertThat(existingProfile.getVersion()).isEqualTo(1L);
        assertThat(existingProfile.getCreatedAt()).isEqualTo(createdAt);
        assertThat(existingProfile.getUpdatedAt()).isAfter(createdAt);
        verify(investmentProfileRepository, never()).saveAndFlush(any(InvestmentProfile.class));

        ArgumentCaptor<ProfileHistory> historyCaptor =
                ArgumentCaptor.forClass(ProfileHistory.class);
        verify(profileHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getVersionNo()).isEqualTo(2L);
        assertThat(historyCaptor.getValue().getRiskScore()).isEqualTo(68L);
    }

    @Test
    @DisplayName("요청 버전이 현재 버전과 다르면 저장 전에 낙관적 락 예외를 던진다")
    void updateProfileWithStaleVersion() {
        // given
        Long userId = 1L;
        OffsetDateTime createdAt = OffsetDateTime.now().minusDays(1);
        InvestmentProfile existingProfile = InvestmentProfile.builder()
                .userId(userId)
                .riskScore(30L)
                .riskGrade("STABLE_SEEKING")
                .profileSummary("기존 요약")
                .investmentGoal("기존 목표")
                .answersSnapshot(Map.of("riskScore", 30L))
                .version(2L)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
        ProfileUpdateRequest request = new ProfileUpdateRequest(validAnswers(), null, 1L);

        when(investmentProfileRepository.findById(userId)).thenReturn(Optional.of(existingProfile));

        // when & then
        assertThatThrownBy(() -> strategyProfileService.updateProfile(userId, request))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(investmentProfileRepository, never()).saveAndFlush(any(InvestmentProfile.class));
        verify(profileHistoryRepository, never()).save(any(ProfileHistory.class));
    }

    @Test
    @DisplayName("기존 프로필 수정 요청에 version이 없으면 저장 전에 예외를 던진다")
    void updateProfileWithoutVersion() {
        // given
        Long userId = 1L;
        OffsetDateTime createdAt = OffsetDateTime.now().minusDays(1);
        InvestmentProfile existingProfile = InvestmentProfile.builder()
                .userId(userId)
                .riskScore(30L)
                .riskGrade("STABLE_SEEKING")
                .profileSummary("기존 요약")
                .investmentGoal("기존 목표")
                .answersSnapshot(Map.of("riskScore", 30L))
                .version(2L)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();

        when(investmentProfileRepository.findById(userId)).thenReturn(Optional.of(existingProfile));

        // when & then
        assertThatThrownBy(() -> strategyProfileService.updateProfile(userId, requestForActiveProfile()))
                .isInstanceOf(ValidationException.class);
        verify(investmentProfileRepository, never()).saveAndFlush(any(InvestmentProfile.class));
        verify(profileHistoryRepository, never()).save(any(ProfileHistory.class));
    }

    @Test
    @DisplayName("동시 최초 생성 중복 키 충돌은 낙관적 락 예외로 변환한다")
    void createProfileWithDuplicateKey() {
        // given
        Long userId = 1L;
        when(investmentProfileRepository.findById(userId)).thenReturn(Optional.empty());
        when(investmentProfileRepository.saveAndFlush(any(InvestmentProfile.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        // when & then
        assertThatThrownBy(() -> strategyProfileService.updateProfile(userId, requestForActiveProfile()))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(profileHistoryRepository, never()).save(any(ProfileHistory.class));
    }

    @Test
    @DisplayName("필수 문항 답변이 누락되면 예외가 발생한다")
    void missingRequiredAnswer() {
        // given
        ProfileUpdateRequest request = new ProfileUpdateRequest(
                List.of(new ProfileAnswerRequest("AGE_GROUP", "AGE_20_TO_40")),
                null,
                null
        );

        // when & then
        assertThatThrownBy(() -> strategyProfileService.updateProfile(1L, request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(InvestmentErrorCode.REQUIRED_ANSWER_MISSING));
    }

    @Test
    @DisplayName("존재하지 않는 선택지를 제출하면 예외가 발생한다")
    void invalidOption() {
        // given
        List<ProfileAnswerRequest> answers = validAnswers();
        answers.set(0, new ProfileAnswerRequest("AGE_GROUP", "INVALID_OPTION"));
        ProfileUpdateRequest request = new ProfileUpdateRequest(answers, null, null);

        // when & then
        assertThatThrownBy(() -> strategyProfileService.updateProfile(1L, request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(InvestmentErrorCode.INVALID_PROFILE_ANSWER));
    }

    @Test
    @DisplayName("중복 문항 답변을 제출하면 예외가 발생한다")
    void duplicateAnswer() {
        // given
        List<ProfileAnswerRequest> answers = validAnswers();
        answers.set(1, new ProfileAnswerRequest("AGE_GROUP", "AGE_41_TO_50"));
        ProfileUpdateRequest request = new ProfileUpdateRequest(answers, null, null);

        // when & then
        assertThatThrownBy(() -> strategyProfileService.updateProfile(1L, request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(InvestmentErrorCode.DUPLICATE_PROFILE_ANSWER));
    }

    private ProfileUpdateRequest requestForActiveProfile() {
        return requestForActiveProfile(null);
    }

    private ProfileUpdateRequest requestForActiveProfile(Long version) {
        return new ProfileUpdateRequest(
                validAnswers(),
                "배당주 위주로 안정적으로 운용하고 싶습니다.",
                version
        );
    }

    private List<ProfileAnswerRequest> validAnswers() {
        return new java.util.ArrayList<>(List.of(
                new ProfileAnswerRequest("AGE_GROUP", "AGE_20_TO_40"),
                new ProfileAnswerRequest("INVESTMENT_PERIOD", "OVER_3_YEARS"),
                new ProfileAnswerRequest("INVESTMENT_EXPERIENCE", "RISK_NEUTRAL_PRODUCTS"),
                new ProfileAnswerRequest("DERIVATIVE_EXPERIENCE", "UNDER_1_YEAR"),
                new ProfileAnswerRequest("LOSS_TOLERANCE", "CAN_TAKE_MINIMUM_LOSS"),
                new ProfileAnswerRequest("INVESTABLE_ASSET_RATIO", "UNDER_50_PERCENT"),
                new ProfileAnswerRequest("MONTHLY_INCOME", "UNDER_5M"),
                new ProfileAnswerRequest("INVESTMENT_PURPOSE", "MARKET_AVERAGE_RETURN"),
                new ProfileAnswerRequest("FINANCIAL_KNOWLEDGE", "KNOW_STRUCTURE_AND_RISK_BASIC")
        ));
    }
}
