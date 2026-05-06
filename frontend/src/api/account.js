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
 * GET /api/v1/accounts/me/summary
 *
 * 한국투자증권 API 실시간 조회 결과 반환
 * 수익률은 서버에서 계산: 평가손익 / 매입금액 * 100
 *
 * @returns {{
 *   totalAssets: number,      // 총 자산 (원)
 *   principal: number,        // 총 매입금액 (원)
 *   totalPnL: number,         // 총 평가손익 (원)
 *   returnRate: number,       // 수익률 (%)
 *   availableCash: number     // 예수금 (원)
 * }}
 */
export async function getAccountSummary() {
  const data = await apiClient('/accounts/me/summary');
  // data.data: AccountSummaryResponse
  return data.data;
}

/**
 * 사용자 보유 종목(포트폴리오) 조회
 * GET /api/v1/accounts/me/holdings
 *
 * - 보유수량 0인 종목(당일 전량 매도) 제외
 * - 한 번에 최대 50건 조회
 *
 * @returns {Array<{
 *   name: string,           // 종목명
 *   code: string,           // 종목 코드
 *   quantity: number,       // 보유 수량 (주)
 *   avgPrice: number,       // 평균 매입가 (원)
 *   currentPrice: number,   // 현재가 (원)
 *   pnl: number,            // 평가손익 (원)
 *   returnRate: number      // 수익률 (%)
 * }>}
 */
export async function getPortfolio() {
  const data = await apiClient('/accounts/me/holdings');
  // data.data: PortfolioResponse (내부에 holdings 배열이 있을 수 있음 - 백엔드 DTO 확인 필요)
  return data.data;
}
