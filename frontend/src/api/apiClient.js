/**
 * 공통 API 클라이언트 유틸리티
 *
 * - 백엔드 베이스 URL: /api/v1 (vite.config.js의 proxy 설정으로 백엔드 8080으로 포워딩됨)
 * - 인증: Access Token은 메모리에 보관 → 매 요청에 Authorization: Bearer 헤더로 전송
 * - 401 응답 시 /auth/refresh로 새 Access Token을 발급받아 메모리 갱신 후 원 요청 1회 재시도
 * - Refresh Token은 HttpOnly 쿠키로 서버가 관리 (credentials: 'include'로 자동 전송됨)
 */

const BASE_URL = '/api/v1';

// 메모리 토큰 저장 — 페이지 새로고침 시 비워지며, 첫 401에서 refresh로 자동 복구된다.
let accessToken = null;

// 동시 다발 401 발생 시 refresh가 한 번만 실행되도록 promise를 공유
let refreshPromise = null;

/**
 * Access Token을 메모리에 저장한다. 로그인/소셜로그인 응답을 받은 직후 호출.
 */
export function setAccessToken(token) {
  accessToken = token;
}

/**
 * 메모리의 Access Token을 제거한다. 로그아웃 시 호출.
 */
export function clearAccessToken() {
  accessToken = null;
}

/**
 * 현재 메모리에 저장된 Access Token을 반환한다.
 */
export function getAccessToken() {
  return accessToken;
}

/**
 * Refresh Token 쿠키로 새 Access Token을 발급받아 메모리에 저장한다.
 * 동시에 여러 번 호출되어도 한 번만 실제 네트워크 요청을 보낸다.
 */
async function callRefresh() {
  if (refreshPromise) return refreshPromise;

  refreshPromise = (async () => {
    const response = await fetch(`${BASE_URL}/auth/refresh`, {
      method: 'POST',
      credentials: 'include',
    });
    const data = await response.json().catch(() => null);
    if (!response.ok || !data?.success || !data?.data?.accessToken) {
      throw new Error('토큰 재발급 실패');
    }
    accessToken = data.data.accessToken;
    return accessToken;
  })();

  try {
    return await refreshPromise;
  } finally {
    refreshPromise = null;
  }
}

/**
 * 공통 fetch 래퍼
 *
 * @param {string} endpoint - /auth/social/kakao 처럼 BASE_URL 이후의 경로
 * @param {RequestInit} options - fetch 옵션 (method, body, headers 등)
 * @param {boolean} retry - 401 시 재시도 여부 (무한루프 방지용, 내부적으로만 사용)
 * @returns {Promise<any>} - 응답 JSON 전체 ({ success, message, errorCode, traceId, data })
 */
async function apiClient(endpoint, options = {}, retry = true) {
  const url = `${BASE_URL}${endpoint}`;

  const headers = {
    'Content-Type': 'application/json',
    ...(options.headers ?? {}),
  };
  if (accessToken) {
    headers['Authorization'] = `Bearer ${accessToken}`;
  }

  const fetchOptions = {
    ...options,
    credentials: 'include', // refreshToken 쿠키 전송용
    headers,
  };

  const response = await fetch(url, fetchOptions);

  // 401 Unauthorized: Access Token 만료 → refreshToken 쿠키로 재발급 후 1회 재시도
  if (response.status === 401 && retry) {
    try {
      await callRefresh();
      return apiClient(endpoint, options, false);
    } catch {
      clearAccessToken();
      if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
        window.location.href = '/login';
      }
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
