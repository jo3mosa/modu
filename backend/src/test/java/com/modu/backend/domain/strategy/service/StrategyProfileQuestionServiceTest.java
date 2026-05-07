package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.strategy.dto.ProfileQuestionListResponse;
import com.modu.backend.domain.strategy.dto.ProfileQuestionResponse;
import com.modu.backend.domain.strategy.dto.ProfileQuestionScoringType;
import com.modu.backend.domain.strategy.dto.ProfileQuestionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyProfileQuestionServiceTest {

    private final StrategyProfileQuestionService service = new StrategyProfileQuestionService();

    @Test
    @DisplayName("투자 성향 설문 문항 9개를 조회한다")
    void getProfileQuestions() {
        // when
        ProfileQuestionListResponse result = service.getProfileQuestions();

        // then
        assertThat(result.questions()).hasSize(9);
        assertThat(result.questions())
                .extracting(ProfileQuestionResponse::questionId)
                .containsExactly(
                        "AGE_GROUP",
                        "INVESTMENT_PERIOD",
                        "INVESTMENT_EXPERIENCE",
                        "DERIVATIVE_EXPERIENCE",
                        "LOSS_TOLERANCE",
                        "INVESTABLE_ASSET_RATIO",
                        "MONTHLY_INCOME",
                        "INVESTMENT_PURPOSE",
                        "FINANCIAL_KNOWLEDGE"
                );
    }

    @Test
    @DisplayName("문항 응답에는 점수를 노출하지 않는다")
    void hideScoreFromResponse() {
        // when
        ProfileQuestionListResponse result = service.getProfileQuestions();

        // then
        ProfileQuestionResponse ageQuestion = result.questions().get(0);
        assertThat(ageQuestion.type()).isEqualTo(ProfileQuestionType.SINGLE);
        assertThat(ageQuestion.required()).isTrue();
        assertThat(ageQuestion.scoringType()).isEqualTo(ProfileQuestionScoringType.SCORED);
        assertThat(ageQuestion.options().get(0).optionId()).isEqualTo("UNDER_19");
        assertThat(ageQuestion.options().get(0).label()).isEqualTo("만 19세 이하");
    }

    @Test
    @DisplayName("파생상품 등 투자경험 문항은 점수 산정 제외로 응답한다")
    void derivativeExperienceIsExcludedFromScoring() {
        // when
        ProfileQuestionListResponse result = service.getProfileQuestions();

        // then
        ProfileQuestionResponse derivativeQuestion = result.questions().get(3);
        assertThat(derivativeQuestion.questionId()).isEqualTo("DERIVATIVE_EXPERIENCE");
        assertThat(derivativeQuestion.scoringType()).isEqualTo(ProfileQuestionScoringType.EXCLUDED);
    }
}
