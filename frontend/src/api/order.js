/**
 * 주문(Order) 관련 API 함수
 * 베이스 경로: /api/v1/orders
 */
import apiClient from './apiClient';

/**
 * 미체결 주문 조회
 * GET /api/v1/orders/pending
 *
 * 응답 루트는 객체 `{ pendingOrders: [...] }`. 본 함수는 배열만 추출해 반환.
 *
 * @returns {Promise<Array<{
 *   orderId: string | null,
 *   stockCode: string,
 *   stockName: string,
 *   side: 'BUY' | 'SELL',
 *   orderType: 'LIMIT' | 'MARKET',
 *   quantity: number,
 *   price: number,
 *   filledQuantity: number,
 *   remainQuantity: number,
 *   source: 'AUTO' | 'MANUAL' | null,
 *   createdAt: string | null
 * }>>}
 */
export async function getPendingOrders() {
  const data = await apiClient('/orders/pending');
  return data.data?.pendingOrders ?? [];
}

/**
 * 주문 가능 금액/수량 조회
 * GET /api/v1/orders/buying-power?stockCode=&side=&orderPrice=
 *
 * 백엔드 명세:
 * - stockCode (필수)
 * - side: 'BUY' | 'SELL' (필수)
 * - orderPrice (선택) — 주문 희망 가격
 *
 * 응답:
 * - maxBuyAmount: 최대 매수 가능 금액 (원, Long)
 * - maxBuyQuantity: 최대 매수 가능 수량 (side=BUY 시 제공, SELL 시 0)
 * - maxSellQuantity: 최대 매도 가능 수량 (side=SELL 시 제공, BUY 시 0)
 * - availableCash: 현재 예수금 (Long)
 *
 * @param {{ stockCode: string, side: 'BUY' | 'SELL', orderPrice?: number }} params
 * @returns {Promise<{
 *   maxBuyAmount: number,
 *   maxBuyQuantity: number,
 *   maxSellQuantity: number,
 *   availableCash: number
 * }>}
 */
export async function getBuyingPower({ stockCode, side, orderPrice } = {}) {
  const search = new URLSearchParams();
  if (stockCode) search.set('stockCode', stockCode);
  if (side) search.set('side', side);
  if (orderPrice != null) search.set('orderPrice', String(orderPrice));
  const data = await apiClient(`/orders/buying-power?${search.toString()}`);
  return data.data;
}

/**
 * 수동 주문 실행 (매수/매도)
 * POST /api/v1/orders
 *
 * 백엔드 검증 (OrderRequest):
 * - stockCode: @NotBlank
 * - side: 'BUY' | 'SELL' (OrderSide enum)
 * - quantity: @Min(1) Integer
 * - price: @Min(0) Long. orderMethod=LIMIT일 때 > 0 필수
 * - orderMethod: 'LIMIT' | 'MARKET' (OrderType enum)
 *
 * Idempotency-Key 헤더로 중복 주문 방지 가능. 미전송 시 서버에서 자동 생성.
 * 응답 코드: 201 Created. 응답 status는 PENDING/FILLED/CANCELED/MODIFIED/REJECTED.
 *
 * @param {{
 *   stockCode: string,
 *   side: 'BUY' | 'SELL',
 *   orderMethod: 'LIMIT' | 'MARKET',
 *   quantity: number,
 *   price: number
 * }} payload
 * @param {string} [idempotencyKey]
 * @returns {Promise<{
 *   orderId: string,
 *   stockCode: string,
 *   side: 'BUY' | 'SELL',
 *   orderType: 'LIMIT' | 'MARKET',
 *   quantity: number,
 *   price: number,
 *   status: 'PENDING' | 'FILLED' | 'CANCELED' | 'MODIFIED' | 'REJECTED',
 *   createdAt: string
 * }>}
 */
export async function placeOrder(payload, idempotencyKey) {
  const headers = {};
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  const data = await apiClient('/orders', {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
  });
  return data.data;
}

/**
 * 미체결 주문 정정/취소
 * PATCH /api/v1/orders/{orderId}
 *
 * 백엔드 검증 (ModifyOrderRequest):
 * - action: 'MODIFY' | 'CANCEL' (필수)
 * - MODIFY: newQuantity 또는 newPrice 중 하나 이상 필수 (@AssertTrue)
 * - newQuantity: @Min(1), newPrice: @Min(1)
 * - CANCEL: newQuantity/newPrice 불필요 (잔량 전량 취소)
 *
 * 에러: ORDER_004(이미 체결), ORDER_006(본인 주문 아님)
 * 정정 후 KIS에서 새 주문번호 발급 → 재정정/취소 가능
 *
 * @param {string|number} orderId
 * @param {{
 *   action: 'MODIFY' | 'CANCEL',
 *   newQuantity?: number,
 *   newPrice?: number
 * }} payload
 * @returns {Promise<{
 *   orderId: string,
 *   status: 'MODIFIED' | 'CANCELED',
 *   updatedAt: string
 * }>}
 */
export async function updateOrder(orderId, payload) {
  const data = await apiClient(`/orders/${orderId}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
  return data.data;
}

/**
 * 전체 거래 이력 조회 (페이징)
 * GET /api/v1/orders/history?source=&from=&to=&page=&size=
 *
 * 백엔드 명세:
 * - source: 'AUTO' | 'MANUAL' (선택)
 * - from: YYYYMMDD (선택)
 * - to: YYYYMMDD (선택)
 * - page: 1부터 (선택, 기본 1)
 * - size: 페이지 크기 (선택, 기본 20)
 *
 * 응답 루트는 객체. 본 함수는 raw 응답을 그대로 반환한다(orders 배열 + 페이징 메타).
 *
 * @param {{
 *   source?: 'AUTO' | 'MANUAL',
 *   from?: string,
 *   to?: string,
 *   page?: number,
 *   size?: number
 * }} [params]
 * @returns {Promise<{
 *   orders: Array<{
 *     orderId: string,
 *     stockCode: string,
 *     stockName: string,
 *     side: 'BUY' | 'SELL',
 *     orderType: 'LIMIT' | 'MARKET',
 *     quantity: number,
 *     price: number,
 *     status: 'FILLED' | 'CANCELED' | 'PENDING',
 *     source: 'AUTO' | 'MANUAL',
 *     createdAt: string
 *   }>,
 *   totalCount: number,
 *   page: number,
 *   size: number
 * }>}
 */
export async function getOrderHistory({ source, from, to, page, size } = {}) {
  const search = new URLSearchParams();
  if (source) search.set('source', source);
  if (from) search.set('from', from);
  if (to) search.set('to', to);
  if (page != null) search.set('page', String(page));
  if (size != null) search.set('size', String(size));
  const queryString = search.toString();
  const endpoint = queryString ? `/orders/history?${queryString}` : '/orders/history';
  const data = await apiClient(endpoint);
  return data.data;
}