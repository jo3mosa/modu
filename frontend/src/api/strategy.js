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
 * @returns {Promise<{ questions: Array<{
 *   questionId: string,
 *   text: string,
 *   scored: boolean,
 *   options: Array<{ optionId: number, text: string }>
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
 * 프론트는 questionId + optionId 조합만 제출한다. 서버가 점수 산정과
 * 5단계 성향(STABLE / STABLE_SEEKING / RISK_NEUTRAL / ACTIVE / AGGRESSIVE)
 * 등급을 계산해 응답한다.
 *
 * @param {Array<{ questionId: string, optionId: number }>} answers - 전체 9문항 답변 배열
 * @returns {Promise<{
 *   riskScore: number,
 *   riskGrade: string,
 *   profileSummary: string,
 *   onboarding: { isSurveyCompleted: boolean, isRuleSetCompleted: boolean }
 * }>}
 */
export async function updateProfile(answers) {
  const response = await apiClient('/strategies/me/profiles', {
    method: 'PATCH',
    body: JSON.stringify({ answers }),
  });
  return response.data;
}

/**
 * 투자 리스크 룰셋 설정 및 수정
 * PUT /api/v1/strategies/me/rules
 *
 * @param {{ principle: string, takeProfit: number, stopLoss: number, positionSize: string }} payload
 */
export async function updateRules(payload) {
  await apiClient('/strategies/me/rules', {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}
