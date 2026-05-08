/**
 * 인증(Auth) 관련 API 함수
 * 베이스 경로: /api/v1/auth
 *
 * 인증 토큰 정책 (백엔드 변경 반영):
 * - Access Token: 응답 본문(data.accessToken)으로 받아 메모리에 저장 → 매 요청 Authorization 헤더로 전송
 * - Refresh Token: 백엔드가 HttpOnly 쿠키로 발급/관리 (프론트는 직접 접근 불가)
 */
import apiClient, { setAccessToken, clearAccessToken } from './apiClient';

/**
 * 카카오 소셜 로그인
 * POST /api/v1/auth/social/kakao
 *
 * 흐름:
 * 1. 프론트(KakaoCallbackPage)가 카카오 인증 후 URL에서 code 파라미터 추출
 * 2. 이 함수로 code 전달
 * 3. 백엔드가 카카오 API와 통신 → 사용자 정보 조회 → JWT 발급
 * 4. 응답 본문의 accessToken을 메모리에 저장, refreshToken은 쿠키로 자동 저장됨
 *
 * @param {string} code - 카카오 인증 서버로부터 받은 인가 코드
 * @returns {Promise<{
 *   accessToken: string,
 *   userId: number,
 *   nickname: string,
 *   email: string | null,
 *   onboarding: { isSurveyCompleted: boolean, isRuleSetCompleted: boolean }
 * }>}
 */
export async function socialLogin(code) {
  const data = await apiClient('/auth/social/kakao', {
    method: 'POST',
    body: JSON.stringify({ code }),
  });
  if (data?.data?.accessToken) {
    setAccessToken(data.data.accessToken);
  }
  return data.data;
}

/**
 * [개발용] 테스트 우회 로그인
 * POST /api/v1/auth/test/login
 *
 * - 로컬 개발 환경(Spring Profile: local)에서만 활성화됨
 * - DB에 존재하는 userId로만 로그인 가능
 * - 응답 본문의 accessToken을 메모리에 저장, refreshToken은 쿠키로 자동 저장됨
 *
 * @param {number} userId - DB에 등록된 사용자 ID
 * @returns {Promise<{
 *   accessToken: string,
 *   userId: number,
 *   nickname: string,
 *   email: string | null,
 *   onboarding: { isSurveyCompleted: boolean, isRuleSetCompleted: boolean }
 * }>}
 */
export async function testLogin(userId) {
  const data = await apiClient('/auth/test/login', {
    method: 'POST',
    body: JSON.stringify({ userId }),
  });
  if (data?.data?.accessToken) {
    setAccessToken(data.data.accessToken);
  }
  return data.data;
}

/**
 * 로그아웃
 * POST /api/v1/auth/logout
 *
 * - 백엔드에서 Access Token을 Redis 블랙리스트에 등록(헤더의 Bearer 토큰 기준)하고
 *   refreshToken 쿠키를 폐기·만료 처리한다.
 * - 호출 후 메모리의 accessToken도 즉시 제거한다.
 */
export async function logout() {
  try {
    await apiClient('/auth/logout', { method: 'POST' });
  } finally {
    clearAccessToken();
  }
}
