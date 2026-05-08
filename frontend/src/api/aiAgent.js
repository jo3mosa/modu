/**
 * AI 에이전트 관련 API 함수
 * 베이스 경로: /api/v1/ai-agent
 */
import apiClient from './apiClient';

/**
 * AI 판단 전체 이력 조회
 * GET /api/v1/ai-agent/decisions
 *
 * @returns {Array<{
 *   decisionId: string,
 *   orderId: string,
 *   stockCode: string,
 *   stockName: string,
 *   decisionType: string, // 'BUY' | 'SELL' | 'HOLD'
 *   reason: string,       // 판단 근거 요약
 *   confidence: number,   // 확신도 (0~100)
 *   decidedAt: string     // 판단 시각
 * }>}
 */
export async function getAiDecisions() {
  const data = await apiClient('/ai-agent/decisions');
  return data.data;
}

/**
 * 주문별 AI 판단 근거 조회
 * GET /api/v1/ai-agent/decisions/orders/{orderId}
 *
 * @param {string} orderId 
 * @returns {{
 *   orderId: string,
 *   decisionType: string,
 *   detailedReason: string,
 *   marketDataSnapshot: object,
 *   decidedAt: string
 * }}
 */
export async function getAiDecisionByOrder(orderId) {
  const data = await apiClient(`/ai-agent/decisions/orders/${orderId}`);
  return data.data;
}
