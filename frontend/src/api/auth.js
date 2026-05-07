/**
 * 인증(Auth) 관련 API 함수
 * 베이스 경로: /api/v1/auth
 */
import apiClient from './apiClient';

/**
 * 카카오 소셜 로그인
 * POST /api/v1/auth/social/kakao
 *
 * 흐름:
 * 1. 프론트(KakaoCallbackPage)가 카카오 인증 후 URL에서 code 파라미터 추출
 * 2. 이 함수로 code 전달
 * 3. 백엔드가 카카오 API와 통신 → 사용자 정보 조회 → JWT 발급
 * 4. 응답 쿠키로 accessToken(1시간), refreshToken(14일) 자동 저장
 *
 * @param {string} code - 카카오 인증 서버로부터 받은 인가 코드
 * @returns {Promise<{
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
  // data.data: LoginResponse { onboarding: { isSurveyCompleted, isRuleSetCompleted } }
  return data.data;
}

/**
 * [개발용] 테스트 우회 로그인
 * POST /api/v1/auth/test/login
 *
 * - 로컬 개발 환경(Spring Profile: local)에서만 활성화됨
 * - DB에 존재하는 userId로만 로그인 가능
 * - 응답 쿠키로 accessToken, refreshToken 자동 저장
 *
 * @param {number} userId - DB에 등록된 사용자 ID
 * @returns {Promise<{
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
  // data.data: LoginResponse { onboarding: { isSurveyCompleted, isRuleSetCompleted } }
  return data.data;
}

/**
 * 로그아웃
 * POST /api/v1/auth/logout
 *
 * - 서버에서 refreshToken 폐기 + 클라이언트 쿠키(accessToken, refreshToken) 만료 처리
 * - accessToken이 만료된 상태에서도 호출 가능
 * - refreshToken 쿠키가 없어도 성공 처리됨
 */
export async function logout() {
  await apiClient('/auth/logout', { method: 'POST' });
}
