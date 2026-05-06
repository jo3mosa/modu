/**
 * 주문(Order) 관련 API 함수
 *
 * 백엔드 컨트롤러: OrderController
 * 베이스 경로: /api/v1/orders
 */
import apiClient from './apiClient';

/**
 * 미체결 주문 조회
 * GET /api/v1/orders/pending
 *
 * @returns {Array<{
 *   orderId: string,
 *   stockCode: string,
 *   stockName: string,
 *   orderType: string,   // 'BUY' | 'SELL'
 *   price: number,
 *   quantity: number,
 *   orderedAt: string
 * }>}
 */
export async function getPendingOrders() {
  const data = await apiClient('/orders/pending');
  return data.data;
}

/**
 * 주문 가능 금액/수량 조회
 * GET /api/v1/orders/buying-power?stockCode={stockCode}&price={price}
 *
 * @param {string} stockCode - 종목 코드
 * @param {number} price - 주문 단가
 * @returns {{
 *   availableCash: number,    // 주문 가능 금액 (원)
 *   maxQuantity: number       // 최대 주문 가능 수량 (주)
 * }}
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
 * @param {{
 *   stockCode: string,
 *   orderType: string,  // 'BUY' | 'SELL'
 *   price: number,
 *   quantity: number
 * }} payload
 * @returns {{
 *   orderId: string,
 *   status: string      // 'PENDING' | 'FILLED' | 'FAILED'
 * }}
 */
export async function placeOrder(payload) {
  const data = await apiClient('/orders', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
  return data.data;
}

/**
 * 미체결 주문 정정/취소
 * PATCH /api/v1/orders/{orderId}
 *
 * @param {string} orderId - 주문 ID
 * @param {{ price?: number, quantity?: number, cancel?: boolean }} payload
 *   cancel: true 이면 취소, 아니면 정정
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
 * @returns {Array<{
 *   orderId: string,
 *   stockCode: string,
 *   stockName: string,
 *   orderType: string,   // 'BUY' | 'SELL'
 *   price: number,
 *   quantity: number,
 *   status: string,      // 'FILLED' | 'CANCELLED' | 'FAILED'
 *   filledAt: string
 * }>}
 */
export async function getOrderHistory() {
  const data = await apiClient('/orders/history');
  return data.data;
}
