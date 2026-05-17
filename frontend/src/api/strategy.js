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
 * 내 투자 성향 조회
 * GET /api/v1/strategies/me/profiles
 *
 * 현재 로그인 사용자의 최신 투자 성향 프로필을 반환한다.
 * 저장된 프로필이 없으면 404 (errorCode: INVEST_001) 반환.
 *
 * @returns {Promise<{
 *   riskLevel: 'STABLE' | 'STABLE_SEEKING' | 'RISK_NEUTRAL' | 'ACTIVE' | 'AGGRESSIVE',
 *   profileSummary: string,
 *   answers: Array<{ questionId: string, question: string, answer: string }>,
 *   freeText: string,
 *   createdAt: string,
 *   updatedAt: string,
 *   version: number
 * }>}
 */
export async function getProfile() {
  const response = await apiClient('/strategies/me/profiles', {
    method: 'GET',
  });
  return response.data;
}

/**
 * 투자 성향 입력/수정
 * PATCH /api/v1/strategies/me/profiles
 *
 * 서버가 점수 산정 및 5단계 투자 성향 등급(InvestmentRiskLevel)을 계산해 응답
 *
 *
 * @param {{
 *   answers: Array<{ questionId: string, optionId: string }>,
 *   freeText?: string,
 *   version: number
 * }} payload
 * @returns {Promise<{
 *   riskLevel: 'STABLE' | 'STABLE_SEEKING' | 'RISK_NEUTRAL' | 'ACTIVE' | 'AGGRESSIVE',
 *   riskScore: number,
 *   profileSummary: string,
 *   createdAt: string,
 *   version: number,
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
 * 내 리스크 룰셋 조회
 * GET /api/v1/strategies/me/rules
 *
 * 저장된 룰셋이 없으면 404 응답. 화면 진입 시 호출하여 ruleVersion 보존에 사용.
 *
 * @returns {Promise<{
 *   stopLossRate: number,
 *   takeProfitRate: number,
 *   maxDailyOrderCount: number,
 *   maxDailyLossAmount: number,
 *   updatedAt: string,
 *   version: number
 * }>}
 */
export async function getRules() {
  const response = await apiClient('/strategies/me/rules', {
    method: 'GET',
  });
  return response.data;
}

/**
 * 리스크 룰셋 갱신
 * PUT /api/v1/strategies/me/rules
 *
 * 백엔드 검증 (RuleUpdateRequest):
 * - stopLossRate, takeProfitRate: @Min(1) 양수 정수 (% 단위, 절대값)
 * - maxDailyOrderCount, maxDailyLossAmount: @Min(1) 양수
 * - version: @Min(0) 낙관적 잠금. 최초 호출은 0, 이후엔 직전 응답의 version
 *
 * @param {{
 *   stopLossRate: number,
 *   takeProfitRate: number,
 *   maxDailyOrderCount: number,
 *   maxDailyLossAmount: number,
 *   version: number
 * }} payload
 * @returns {Promise<{
 *   stopLossRate: number,
 *   takeProfitRate: number,
 *   maxDailyOrderCount: number,
 *   maxDailyLossAmount: number,
 *   updatedAt: string,
 *   version: number
 * }>}
 */
export async function updateRules(payload) {
  const response = await apiClient('/strategies/me/rules', {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
  return response.data;
}

/**
 * 자동매매 ON/OFF 전환
 * PATCH /api/v1/strategies/me/status
 *
 * - isActive=true:  ACTIVE 전환 (KILL_SWITCHED 상태도 함께 해제)
 * - isActive=false: INACTIVE 전환
 * - 503 (errorCode: STRATEGY_*): KILL_SWITCHED 상태이거나 실패 — 호출부에서 사용자 알림
 *
 * @param {{ isActive: boolean }} payload
 * @returns {Promise<{ isActive: boolean, updatedAt: string }>}
 */
export async function updateAutoTradeStatus(payload) {
  const response = await apiClient('/strategies/me/status', {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
  return response.data;
}
