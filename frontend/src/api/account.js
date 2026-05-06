/**
 * 자산/계좌(Account) 관련 API 함수
 *
 * 백엔드 컨트롤러: AccountController
 * 베이스 경로: /api/v1/accounts/me
 *
 * 공통 주의사항:
 * - KIS API 미연동 시 404(errorCode: KIS_NOT_CONNECTED) 반환 → 마이페이지 안내 필요
 * - KIS API 호출 실패 시 502 반환 (토큰 만료, 네트워크 오류 등)
 */
import apiClient from './apiClient';

/**
 * 사용자 계좌 자산 요약 조회
 * GET /api/v1/accounts/assets
 *
 * @returns {{
 *   totalAsset: number,        // 총 자산 (예수금 + 총 평가 금액) (원)
 *   availableCash: number,     // 주문 가능 현금 D+2 예수금 (원)
 *   totalEvalAmount: number,   // 주식 총 평가 금액 (원)
 *   totalBuyAmount: number,    // 주식 총 매입 금액 (원)
 *   totalPnl: number,          // 총 평가 손익 금액 (원)
 *   totalPnlPct: number        // 총 수익률 (%)
 * }}
 */
export async function getAccountSummary() {
  const data = await apiClient('/accounts/assets');
  return data.data;
}

/**
 * 사용자 보유 종목(포트폴리오) 조회
 * GET /api/v1/accounts/portfolio
 *
 * @returns {{ holdings: Array<{
 *   stockCode: string,      // 종목 코드
 *   stockName: string,      // 종목명
 *   quantity: number,       // 보유 수량 (주)
 *   avgBuyPrice: number,    // 매입 단가 (원)
 *   currentPrice: number,   // 현재가 (원)
 *   pnl: number,            // 평가 손익 금액 (원)
 *   pnlPct: number          // 수익률 (%)
 * }> }}
 */
export async function getPortfolio() {
  const data = await apiClient('/accounts/portfolio');
  return data.data;
}
