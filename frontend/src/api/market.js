/**
 * 시장/종목(Market) 관련 API 함수
 * 베이스 경로: /api/v1/markets/stocks
 */
import apiClient from './apiClient';

/**
 * 종목 전체 조회 / 종목 검색
 * GET /api/v1/markets/stocks?keyword={검색어}&page={페이지}&size={크기}
 *
 * - keyword 없으면 전체 종목 목록 반환
 * - keyword 있으면 종목명 또는 종목코드 부분 일치 검색
 * - 응답 항목에는 stockCode/stockName/marketType만 포함 (시세는 미포함)
 *
 * @param {{ keyword?: string, page?: number, size?: number }} [params]
 *   keyword: 검색어 (종목명 or 종목코드)
 *   page: 페이지 번호 (1 이상, 기본 1)
 *   size: 페이지 크기 (1~100, 기본 20)
 * @returns {{
 *   stocks: Array<{ stockCode: string, stockName: string, marketType: string }>,
 *   totalCount: number,
 *   page: number,
 *   size: number
 * }}
 */
export async function getStocks({ keyword, page, size } = {}) {
  const search = new URLSearchParams();
  if (keyword) search.set('keyword', keyword);
  if (page != null) search.set('page', String(page));
  if (size != null) search.set('size', String(size));

  const queryString = search.toString();
  const endpoint = queryString ? `/markets/stocks?${queryString}` : '/markets/stocks';
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
 *   marketType: string,        // 'KOSPI' | 'KOSDAQ'
 *   currentPrice: number,      // 현재가 (원)
 *   compareRate: number,       // 전일 대비율 (%)
 *   compareSign: string,       // 전일 대비 부호 (1,2:상승 / 3:보합 / 4,5:하락)
 *   accumulatedVolume: number, // 누적 거래량
 *   marketCap: number,         // 시가총액 (원)
 *   openPrice: number,         // 당일 시가
 *   highPrice: number,         // 당일 고가
 *   lowPrice: number           // 당일 저가
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
