/**
 * 주문(Order) 관련 API 함수
 * 베이스 경로: /api/v1/orders
 */
import apiClient from './apiClient';

/**
 * 미체결 주문 조회
 * GET /api/v1/orders/pending
 *
 * ⚠️ 백엔드 develop 브랜치에 추가됐지만, 워킹트리에 머지되기 전엔 404 가능.
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
 * GET /api/v1/orders/buying-power?stockCode={stockCode}&price={price}
 *
 * ⚠️ 백엔드 미구현. 호출 시 404.
 *
 * @param {string} stockCode
 * @param {number} price
 * @returns {Promise<{ availableCash: number, maxQuantity: number }>}
 */
export async function getBuyingPower(stockCode, price) {
  const data = await apiClient(
    `/orders/buying-power?stockCode=${encodeURIComponent(stockCode)}&price=${price}`
  );
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
 * ⚠️ 백엔드 미구현 (별도 브랜치 작업 중). 호출 시 404.
 *
 * @param {string} orderId
 * @param {{ price?: number, quantity?: number, cancel?: boolean }} payload
 */
export async function updateOrder(orderId, payload) {
  const data = await apiClient(`/orders/${orderId}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
  return data.data;
}

/**
 * 전체 거래 이력 조회
 * GET /api/v1/orders/history
 *
 * ⚠️ 백엔드 미구현. 호출 시 404.
 *
 * @returns {Promise<Array<{
 *   orderId: string,
 *   stockCode: string,
 *   stockName: string,
 *   side: 'BUY' | 'SELL',
 *   orderType: 'LIMIT' | 'MARKET',
 *   quantity: number,
 *   price: number,
 *   status: string,
 *   filledAt: string
 * }>>}
 */
export async function getOrderHistory() {
  const data = await apiClient('/orders/history');
  return data.data;
}
