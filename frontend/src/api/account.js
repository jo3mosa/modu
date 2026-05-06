/**
 * 자산/계좌(Account) 관련 API 함수
 *
 * 백엔드 컨트롤러: AccountController
 * 베이스 경로: /api/v1/accounts/me
 */
import apiClient from './apiClient';

/**
 * 사용자 계좌 자산 요약 조회
 * GET /api/v1/accounts/me/summary
 *
 * @returns {{
 *   totalAsset: number,
 *   availableCash: number,
 *   totalEvalAmount: number,
 *   totalBuyAmount: number,
 *   totalPnl: number,
 *   totalPnlPct: number
 * }}
 */
export async function getAccountSummary() {
  const data = await apiClient('/accounts/me/summary');
  return data.data;
}

/**
 * 사용자 보유 종목(포트폴리오) 조회
 * GET /api/v1/accounts/me/holdings
 *
 * @returns {{ holdings: Array<{
 *   stockCode: string,
 *   stockName: string,
 *   quantity: number,
 *   avgBuyPrice: number,
 *   currentPrice: number,
 *   pnl: number,
 *   pnlPct: number
 * }> }}
 */
export async function getPortfolio() {
  const data = await apiClient('/accounts/me/holdings');
  return data.data;
}
