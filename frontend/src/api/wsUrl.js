// 실시간 시세 WebSocket URL 빌더 (S14P31B106-345)
//
// REMOTE 모드 backend 는 handshake 시 ?token=<JWT> 로 사용자 식별 — 토큰 없으면 거부.
// LOCAL 모드 backend 는 토큰 없어도 통과(0L fallback) — 호환성 유지.

import { getAccessToken } from './apiClient';

/**
 * @param {string} stockCode 6자리 종목코드
 * @param {'price' | 'orderbook'} kind 스트림 종류
 * @returns {string} ws:// 또는 wss:// 풀 URL
 */
export function buildStockWsUrl(stockCode, kind) {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const token = getAccessToken();
  const tokenQuery = token ? `?token=${encodeURIComponent(token)}` : '';
  return `${protocol}//${window.location.host}/ws/stocks/${stockCode}/${kind}${tokenQuery}`;
}
