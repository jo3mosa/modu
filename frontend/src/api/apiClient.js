/**
 * 공통 API 클라이언트 유틸리티
 *
 * - 백엔드 베이스 URL: /api/v1 (vite.config.js의 proxy 설정으로 백엔드 8080으로 포워딩됨)
 * - 401 응답 시 /auth/refresh로 토큰 재발급 후 원래 요청 1회 자동 재시도
 */

const BASE_URL = '/api/v1';

// 토큰 재발급 중복 호출 방지 플래그
let isRefreshing = false;

/**
 * 토큰 재발급 요청
 * POST /api/v1/auth/refresh
 * 요청 쿠키의 refreshToken을 검증 후 새 accessToken + refreshToken 발급
 */
async function refreshToken() {
  const response = await fetch(`${BASE_URL}/auth/refresh`, {
    method: 'POST',
    credentials: 'include', // 쿠키 자동 전송 (refreshToken 쿠키 포함)
  });
  if (!response.ok) {
    throw new Error('토큰 재발급 실패 - 다시 로그인이 필요합니다.');
  }
}

/**
 * 공통 fetch 래퍼
 *
 * @param {string} endpoint - /auth/social/kakao 처럼 BASE_URL 이후의 경로
 * @param {RequestInit} options - fetch 옵션 (method, body, headers 등)
 * @param {boolean} retry - 401 시 재시도 여부 (무한루프 방지용, 내부적으로만 사용)
 * @returns {Promise<any>} - 응답 JSON 데이터 (data.data 필드)
 */
async function apiClient(endpoint, options = {}, retry = true) {
  const url = `${BASE_URL}${endpoint}`;

  const defaultOptions = {
    credentials: 'include', // 쿠키 자동 첨부 (accessToken, refreshToken)
    headers: {
      'Content-Type': 'application/json',
      ...options.headers,
    },
    ...options,
  };

  const response = await fetch(url, defaultOptions);

  // 401 Unauthorized: accessToken 만료 → refreshToken으로 재발급 후 재시도
  if (response.status === 401 && retry && !isRefreshing) {
    try {
      isRefreshing = true;
      await refreshToken();
      isRefreshing = false;
      // 재발급 성공 → 원래 요청 1회 재시도 (retry=false로 무한루프 방지)
      return apiClient(endpoint, options, false);
    } catch {
      isRefreshing = false;
      // 재발급도 실패 → 로그인 페이지로 리다이렉트
      window.location.href = '/login';
      throw new Error('세션이 만료되었습니다. 다시 로그인해 주세요.');
    }
  }

  const data = await response.json();

  if (!response.ok) {
    // 백엔드 공통 에러 형식: { success: false, message: '...', errorCode: '...' }
    const error = new Error(data.message || `HTTP ${response.status} 에러`);
    error.errorCode = data.errorCode;
    error.status = response.status;
    throw error;
  }

  return data; // { success: true, message: '...', data: { ... } }
}

export default apiClient;
