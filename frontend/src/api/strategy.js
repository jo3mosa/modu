/**
 * 투자 전략(Strategy) 관련 API 함수
 * 베이스 경로: /api/v1/strategies/me
 */
import apiClient from './apiClient';

/**
 * 투자 성향 정보 설정 및 수정
 * PATCH /api/v1/strategies/me/profiles
 *
 * @param {{ horizon: string, goal: string, riskTolerance: string }} payload
 */
export async function updateProfile(payload) {
  await apiClient('/strategies/me/profiles', {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
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
