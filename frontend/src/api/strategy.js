/**
 * 투자 전략(Strategy) 관련 API 함수
 * 베이스 경로: /api/v1/strategies
 */
import apiClient from './apiClient';

/**
 * 투자 성향 설문 문항 조회
 * GET /api/v1/strategies/profile-questions
 *
 * 9개 문항과 선택지를 서버에서 받아온다. 선택지별 점수는 노출되지 않는다.
 *
 * 응답 구조:
 * - questions[].questionId: 문항 고유 ID
 * - questions[].question: 문항 본문 텍스트
 * - questions[].type: 문항 분류 enum (예: INVESTMENT_PERIOD 등)
 * - questions[].required: 필수 응답 여부
 * - questions[].scoringType: 점수 산정 방식 (SCORED / EXCLUDED 등)
 * - questions[].options[].optionId: 선택지 고유 ID (문자열)
 * - questions[].options[].label: 선택지 표시 텍스트
 *
 * @returns {Promise<{ questions: Array<{
 *   questionId: string,
 *   question: string,
 *   type: string,
 *   required: boolean,
 *   scoringType: string,
 *   options: Array<{ optionId: string, label: string }>
 * }> }>}
 */
export async function getProfileQuestions() {
  const response = await apiClient('/strategies/profile-questions', {
    method: 'GET',
  });
  return response.data;
}

/**
 * 투자 성향 입력/수정
 * PATCH /api/v1/strategies/me/profiles
 *
 * 프론트는 questionId + optionId 조합과 (선택) freeText를 제출한다.
 * 서버가 점수 산정 및 5단계 투자 성향 등급(InvestmentRiskLevel)을 계산해 응답한다.
 *
 * - answers는 정확히 9개 항목이어야 한다 (백엔드 @Size(min=9, max=9)).
 * - freeText는 자유 입력 (생략 가능).
 *
 * @param {{
 *   answers: Array<{ questionId: string, optionId: string }>,
 *   freeText?: string
 * }} payload
 * @returns {Promise<{
 *   riskLevel: 'STABLE' | 'STABLE_SEEKING' | 'RISK_NEUTRAL' | 'ACTIVE' | 'AGGRESSIVE',
 *   riskScore: number,
 *   profileSummary: string,
 *   createdAt: string,
 *   onboarding: { isSurveyCompleted: boolean, isRuleSetCompleted: boolean }
 * }>}
 */
export async function updateProfile(payload) {
  const response = await apiClient('/strategies/me/profiles', {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
  return response.data;
}

/**
 * 투자 리스크 룰셋 설정 및 수정
 * PUT /api/v1/strategies/me/rules
 *
 * ⚠️ 백엔드 strategies/me/rules 엔드포인트는 현재 미구현 상태.
 * 정의는 보존하되 호출 시 404가 반환된다.
 *
 * @param {{ principle: string, takeProfit: number, stopLoss: number, positionSize: string }} payload
 */
export async function updateRules(payload) {
  await apiClient('/strategies/me/rules', {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}
