/**
 * 시장/종목(Market) 관련 API 함수
 *
 * 백엔드 컨트롤러: MarketController
 * 베이스 경로: /api/v1/markets/stocks
 */
import apiClient from './apiClient';

/**
 * 종목 전체 조회 / 종목 검색
 * GET /api/v1/markets/stocks
 * GET /api/v1/markets/stocks?query={검색어}
 *
 * - query 없으면 전체 종목 목록 반환
 * - query 있으면 종목명 또는 종목코드로 검색
 *
 * @param {string} [query] - 검색어 (종목명 or 종목코드), 생략 시 전체 조회
 * @returns {Array<{
 *   stockCode: string,     // 종목 코드
 *   stockName: string,     // 종목명
 *   currentPrice: number,  // 현재가
 *   changeRate: number,    // 등락률 (%)
 *   volume: number         // 거래량
 * }>}
 */
export async function getStocks(query) {
  const endpoint = query
    ? `/markets/stocks?query=${encodeURIComponent(query)}`
    : '/markets/stocks';
  const data = await apiClient(endpoint);
  return data.data;
}

/**
 * 종목 상세 조회
 * GET /api/v1/markets/stocks/{stockCode}
 *
 * @param {string} stockCode - 종목 코드 (예: '005930')
 * @returns {{
 *   stockCode: string,
 *   stockName: string,
 *   currentPrice: number,
 *   changeRate: number,
 *   volume: number,
 *   high: number,    // 당일 고가
 *   low: number,     // 당일 저가
 *   open: number     // 당일 시가
 * }}
 */
export async function getStockDetail(stockCode) {
  const data = await apiClient(`/markets/stocks/${stockCode}`);
  return data.data;
}

/**
 * 캔들 차트 데이터 조회
 * GET /api/v1/markets/stocks/{stockCode}/candles?timeframe={timeframe}
 *
 * @param {string} stockCode - 종목 코드
 * @param {string} [timeframe='1D'] - 봉 단위 ('1m' | '5m' | '15m' | '60m' | '1D' | '1W')
 * @returns {Array<{
 *   time: string,   // 'YYYY-MM-DD' 또는 'YYYY-MM-DD HH:mm'
 *   open: number,
 *   high: number,
 *   low: number,
 *   close: number,
 *   volume: number
 * }>}
 */
export async function getStockCandles(stockCode, timeframe = '1D') {
  const data = await apiClient(`/markets/stocks/${stockCode}/candles?timeframe=${timeframe}`);
  return data.data;
}

/**
 * 개별 종목 뉴스 조회
 * GET /api/v1/markets/stocks/{stockCode}/news
 *
 * @param {string} stockCode - 종목 코드
 * @returns {Array<{
 *   title: string,
 *   source: string,
 *   publishedAt: string,
 *   url: string
 * }>}
 */
export async function getStockNews(stockCode) {
  const data = await apiClient(`/markets/stocks/${stockCode}/news`);
  return data.data;
}

/**
 * 기업 정보 조회 (Non-MVP)
 * GET /api/v1/markets/stocks/{stockCode}/company-info
 *
 * @param {string} stockCode - 종목 코드
 */
export async function getCompanyInfo(stockCode) {
  const data = await apiClient(`/markets/stocks/${stockCode}/company-info`);
  return data.data;
}
