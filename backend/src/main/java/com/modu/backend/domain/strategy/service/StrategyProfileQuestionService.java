package com.modu.backend.domain.strategy.service;

import com.modu.backend.domain.investment.exception.InvestmentErrorCode;
import com.modu.backend.domain.strategy.dto.InvestmentRiskLevel;
import com.modu.backend.domain.strategy.dto.ProfileAnswerRequest;
import com.modu.backend.domain.strategy.dto.ProfileQuestionListResponse;
import com.modu.backend.domain.strategy.dto.ProfileQuestionOptionResponse;
import com.modu.backend.domain.strategy.dto.ProfileQuestionResponse;
import com.modu.backend.domain.strategy.dto.ProfileQuestionScoringType;
import com.modu.backend.domain.strategy.dto.ProfileQuestionType;
import com.modu.backend.global.error.ApiException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StrategyProfileQuestionService {

    private static final boolean REQUIRED = true;
    private static final long MAX_SCORE = 72L;

    private static final List<ProfileQuestion> QUESTIONS = List.of(
            question(
                    "AGE_GROUP",
                    "고객님의 연령대는 어떻게 되시나요?",
                    ProfileQuestionScoringType.SCORED,
                    option("UNDER_19", "만 19세 이하", 6),
                    option("AGE_20_TO_40", "만 20세~40세", 8),
                    option("AGE_41_TO_50", "만 41세~50세", 6),
                    option("AGE_51_TO_60", "만 51세~60세", 4),
                    option("OVER_61", "만 61세 이상", 2)
            ),
            question(
                    "INVESTMENT_PERIOD",
                    "이번 투자금은 어느 정도 기간 동안 운용하실 계획인가요?",
                    ProfileQuestionScoringType.SCORED,
                    option("OVER_3_YEARS", "3년 이상 장기 운용할 계획이에요", 10),
                    option("YEARS_2_TO_3", "2년 이상 ~ 3년 미만 정도 생각하고 있어요", 8),
                    option("YEARS_1_TO_2", "1년 이상 ~ 2년 미만 정도 생각하고 있어요", 6),
                    option("MONTHS_6_TO_12", "6개월 이상 ~ 1년 미만 정도 생각하고 있어요", 4),
                    option("UNDER_6_MONTHS", "6개월 미만의 단기 운용을 생각하고 있어요", 2)
            ),
            question(
                    "INVESTMENT_EXPERIENCE",
                    "지금까지 투자해 본 상품 중 가장 위험도가 높았던 것은 무엇인가요?",
                    ProfileQuestionScoringType.SCORED,
                    option("AGGRESSIVE_PRODUCTS", "공격투자형 상품에 투자해 본 적이 있어요", 10),
                    option("ACTIVE_PRODUCTS", "적극투자형 상품에 투자해 본 적이 있어요", 8),
                    option("RISK_NEUTRAL_PRODUCTS", "위험중립형 상품에 투자해 본 적이 있어요", 6),
                    option("STABLE_GROWTH_PRODUCTS", "안정추구형 상품에 투자해 본 적이 있어요", 4),
                    option("STABLE_PRODUCTS", "안정형 상품 위주로만 투자해 봤어요", 2)
            ),
            question(
                    "DERIVATIVE_EXPERIENCE",
                    "ELS, DLS, 파생결합증권 같은 파생상품 투자 경험은 얼마나 되시나요?",
                    ProfileQuestionScoringType.EXCLUDED,
                    option("UNDER_1_YEAR", "1년 미만이에요", null),
                    option("YEARS_1_TO_3", "1년 이상 ~ 3년 미만이에요", null),
                    option("OVER_3_YEARS", "3년 이상이에요", null)
            ),
            question(
                    "LOSS_TOLERANCE",
                    "투자 중 손실이 발생한다면 어느 정도까지 감내할 수 있나요?",
                    ProfileQuestionScoringType.SCORED,
                    option("HIGH_RISK_HIGH_RETURN", "기대수익이 높다면 큰 변동성도 감수할 수 있어요", 8),
                    option("CAN_TAKE_PRINCIPAL_LOSS", "투자원금 중 일부 손실은 감수할 수 있어요", 6),
                    option("CAN_TAKE_MINIMUM_LOSS", "투자원금에서 최소한의 손실만 감수할 수 있어요", 4),
                    option("PRINCIPAL_MUST_BE_PROTECTED", "어떤 경우에도 투자원금은 보전되어야 해요", 2)
            ),
            question(
                    "INVESTABLE_ASSET_RATIO",
                    "전체 자산 중 투자성 자산이 차지하는 비중은 어느 정도인가요?",
                    ProfileQuestionScoringType.SCORED,
                    option("UNDER_10_PERCENT", "10% 이하", 2),
                    option("UNDER_30_PERCENT", "30% 이하", 4),
                    option("UNDER_50_PERCENT", "50% 이하", 6),
                    option("UNDER_70_PERCENT", "70% 이하", 8),
                    option("OVER_70_PERCENT", "70% 초과", 10)
            ),
            question(
                    "MONTHLY_INCOME",
                    "고객님의 월 소득 구간은 어떻게 되시나요?",
                    ProfileQuestionScoringType.SCORED,
                    option("OVER_5M", "500만원 초과", 6),
                    option("UNDER_5M", "500만원 이하", 5),
                    option("UNDER_3M", "300만원 이하", 4),
                    option("UNDER_2M", "200만원 이하", 3),
                    option("UNDER_1M", "100만원 이하", 2)
            ),
            question(
                    "INVESTMENT_PURPOSE",
                    "이번 투자를 통해 가장 기대하는 목표는 무엇인가요?",
                    ProfileQuestionScoringType.SCORED,
                    option("LIVING_EXPENSE", "생활비나 단기 자금을 안정적으로 운용하고 싶어요", 2),
                    option("EXCESS_RETURN_THAN_DEPOSIT", "예적금보다 조금 높은 수익을 기대해요", 4),
                    option("MARKET_AVERAGE_RETURN", "시장 평균 이상의 수익을 기대해요", 6),
                    option("AGGRESSIVE_CAPITAL_GAIN", "적극적으로 자산을 늘리고 싶어요", 8)
            ),
            question(
                    "FINANCIAL_KNOWLEDGE",
                    "금융투자상품의 구조와 위험에 대해 어느 정도 이해하고 계신가요?",
                    ProfileQuestionScoringType.SCORED,
                    option("NO_EXPERIENCE", "금융투자상품에 투자해 본 경험이 거의 없어요", 0),
                    option("KNOW_STRUCTURE_AND_RISK_BASIC", "주식, 채권, 펀드 같은 일반적인 상품의 구조와 위험을 어느 정도 이해하고 있어요", 4),
                    option("KNOW_STRUCTURE_AND_RISK_ADVANCED", "일반적인 금융투자상품의 구조와 위험을 깊이 이해하고 있어요", 8),
                    option("KNOW_DERIVATIVES", "파생상품을 포함한 대부분의 금융투자상품 구조와 위험을 이해하고 있어요", 12)
            )
    );

    public ProfileQuestionListResponse getProfileQuestions() {
        return new ProfileQuestionListResponse(
                QUESTIONS.stream()
                        .map(ProfileQuestion::toResponse)
                        .toList()
        );
    }

    public ProfileAssessment assess(List<ProfileAnswerRequest> answers) {
        Map<String, ProfileAnswerRequest> answersByQuestionId = validateAnswers(answers);

        long rawScore = QUESTIONS.stream()
                .mapToLong(question -> question.scoreOf(answersByQuestionId.get(question.questionId).optionId()))
                .sum();
        long convertedScore = Math.round(rawScore * 100.0 / MAX_SCORE);
        InvestmentRiskLevel riskLevel = InvestmentRiskLevel.fromScore(convertedScore);

        return new ProfileAssessment(
                convertedScore,
                riskLevel,
                profileSummary(riskLevel),
                investmentGoal(answersByQuestionId),
                QUESTIONS.stream()
                        .map(question -> AnswerSnapshot.from(question, answersByQuestionId.get(question.questionId)))
                        .toList()
        );
    }

    private Map<String, ProfileAnswerRequest> validateAnswers(List<ProfileAnswerRequest> answers) {
        if (answers == null || answers.isEmpty()) {
            throw new ApiException(InvestmentErrorCode.REQUIRED_ANSWER_MISSING);
        }

        Map<String, ProfileQuestion> questionsById = QUESTIONS.stream()
                .collect(Collectors.toMap(
                        ProfileQuestion::questionId,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new
                ));
        Map<String, ProfileAnswerRequest> answersByQuestionId = new LinkedHashMap<>();

        for (ProfileAnswerRequest answer : answers) {
            if (answersByQuestionId.containsKey(answer.questionId())) {
                throw new ApiException(InvestmentErrorCode.DUPLICATE_PROFILE_ANSWER);
            }
            ProfileQuestion question = questionsById.get(answer.questionId());
            if (question == null || !question.hasOption(answer.optionId())) {
                throw new ApiException(InvestmentErrorCode.INVALID_PROFILE_ANSWER);
            }
            answersByQuestionId.put(answer.questionId(), answer);
        }

        Set<String> answeredQuestionIds = answersByQuestionId.keySet();
        boolean hasMissingRequiredAnswer = questionsById.keySet().stream()
                .anyMatch(questionId -> !answeredQuestionIds.contains(questionId));
        if (hasMissingRequiredAnswer) {
            throw new ApiException(InvestmentErrorCode.REQUIRED_ANSWER_MISSING);
        }

        return answersByQuestionId;
    }

    private String investmentGoal(Map<String, ProfileAnswerRequest> answersByQuestionId) {
        ProfileAnswerRequest answer = answersByQuestionId.get("INVESTMENT_PURPOSE");
        return QUESTIONS.stream()
                .filter(question -> question.questionId.equals("INVESTMENT_PURPOSE"))
                .findFirst()
                .map(question -> question.optionLabel(answer.optionId()))
                .orElse(null);
    }

    private String profileSummary(InvestmentRiskLevel riskLevel) {
        return "%s 성향입니다. %s 이하 투자위험도 상품이 적합합니다."
                .formatted(riskLevel.getLabel(), riskLevel.getMaxProductRisk());
    }

    private static ProfileQuestion question(
            String questionId,
            String question,
            ProfileQuestionScoringType scoringType,
            ProfileQuestionOption... options
    ) {
        return new ProfileQuestion(questionId, question, scoringType, List.of(options));
    }

    private static ProfileQuestionOption option(String optionId, String label, Integer score) {
        return new ProfileQuestionOption(optionId, label, score);
    }

    private record ProfileQuestion(
            String questionId,
            String question,
            ProfileQuestionScoringType scoringType,
            List<ProfileQuestionOption> options
    ) {

        private ProfileQuestionResponse toResponse() {
            return new ProfileQuestionResponse(
                    questionId,
                    question,
                    ProfileQuestionType.SINGLE,
                    REQUIRED,
                    scoringType,
                    options.stream()
                            .map(ProfileQuestionOption::toResponse)
                            .toList()
            );
        }

        private boolean hasOption(String optionId) {
            return options.stream().anyMatch(option -> option.optionId.equals(optionId));
        }

        private long scoreOf(String optionId) {
            if (scoringType == ProfileQuestionScoringType.EXCLUDED) {
                return 0L;
            }
            return options.stream()
                    .filter(option -> option.optionId.equals(optionId))
                    .findFirst()
                    .map(ProfileQuestionOption::score)
                    .orElseThrow(() -> new ApiException(InvestmentErrorCode.INVALID_PROFILE_ANSWER));
        }

        private String optionLabel(String optionId) {
            return options.stream()
                    .filter(option -> option.optionId.equals(optionId))
                    .findFirst()
                    .map(ProfileQuestionOption::label)
                    .orElseThrow(() -> new ApiException(InvestmentErrorCode.INVALID_PROFILE_ANSWER));
        }
    }

    private record ProfileQuestionOption(
            String optionId,
            String label,
            Integer score
    ) {

        private ProfileQuestionOptionResponse toResponse() {
            return new ProfileQuestionOptionResponse(optionId, label);
        }
    }

    public record ProfileAssessment(
            long riskScore,
            InvestmentRiskLevel riskLevel,
            String profileSummary,
            String investmentGoal,
            List<AnswerSnapshot> answers
    ) {
    }

    public record AnswerSnapshot(
            String questionId,
            String question,
            String optionId,
            String answer,
            ProfileQuestionScoringType scoringType
    ) {

        private static AnswerSnapshot from(ProfileQuestion question, ProfileAnswerRequest answer) {
            return new AnswerSnapshot(
                    question.questionId,
                    question.question,
                    answer.optionId(),
                    question.optionLabel(answer.optionId()),
                    question.scoringType
            );
        }
    }
}
