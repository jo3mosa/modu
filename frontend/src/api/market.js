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
 * @returns {Promise<{
 *   stocks: Array<{ stockCode: string, stockName: string, marketType: string }>,
 *   totalCount: number,
 *   page: number,
 *   size: number
 * }>}
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
 * @returns {Promise<{
 *   stockCode: string,
 *   stockName: string,
 *   marketType: string,
 *   currentPrice: number,
 *   compareRate: number,
 *   compareSign: string,
 *   accumulatedVolume: number,
 *   marketCap: number,
 *   openPrice: number,
 *   highPrice: number,
 *   lowPrice: number
 * }>}
 */
export async function getStockDetail(stockCode) {
  const data = await apiClient(`/markets/stocks/${stockCode}`);
  return data.data;
}

/**
 * 캔들 차트 데이터 조회
 * GET /api/v1/markets/stocks/{stockCode}/candles?period={period}&startDate={YYYYMMDD}&endDate={YYYYMMDD}
 *
 * 응답은 백엔드 raw 형식 그대로 반환된다 (timestamp/openPrice 등). 차트 라이브러리 호환
 * 형식으로의 매핑은 호출자(TradingChart)에서 처리한다.
 *
 * @param {string} stockCode - 종목 코드 (6자리)
 * @param {{
 *   period: 'D' | 'W' | 'M' | '1' | '5' | '60',
 *   startDate?: string,
 *   endDate?: string
 * }} params
 *   period: 일봉(D), 주봉(W), 월봉(M), 분봉(1/5/60). 필수.
 *   startDate/endDate: YYYYMMDD 형식. 생략 시 백엔드의 period별 기본값 적용.
 * @returns {Promise<{
 *   stockCode: string,
 *   period: string,
 *   candles: Array<{
 *     timestamp: string,
 *     openPrice: number,
 *     highPrice: number,
 *     lowPrice: number,
 *     closePrice: number,
 *     volume: number
 *   }>
 * }>}
 */
export async function getStockCandles(stockCode, { period, startDate, endDate } = {}) {
  const search = new URLSearchParams();
  if (period) search.set('period', period);
  if (startDate) search.set('startDate', startDate);
  if (endDate) search.set('endDate', endDate);
  const queryString = search.toString();
  const endpoint = queryString
    ? `/markets/stocks/${stockCode}/candles?${queryString}`
    : `/markets/stocks/${stockCode}/candles`;
  const data = await apiClient(endpoint);
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
 * 기업 정보 조회
 * GET /api/v1/markets/stocks/{stockCode}/company-info
 *
 * @param {string} stockCode - 종목 코드
 */
export async function getCompanyInfo(stockCode) {
  const data = await apiClient(`/markets/stocks/${stockCode}/company-info`);
  return data.data;
}
