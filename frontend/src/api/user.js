/**
 * 사용자(User) 관련 API 함수
 * 베이스 경로: /api/v1/users/me
 *
 * KIS API 연동 관리 (등록 / 수정 / 삭제)
 */
import apiClient from './apiClient';

/**
 * 한국투자증권 API 키 신규 등록
 * POST /api/v1/users/me/kis-keys
 *
 * - 사용자당 하나의 KIS 계정만 연동 가능
 * - 이미 연동된 경우 409(USER_001) 반환 → updateKisKey() 사용
 * - appKey, appSecret은 백엔드에서 AES-256-GCM으로 암호화 저장
 *
 * @param {{ appKey: string, appSecret: string, accountNo: string, isRealAccount: boolean }} payload
 *   accountNo 형식: "계좌번호-상품코드" (예: "50012345-01")
 */
export async function registerKisKey(payload) {
  // payload: { appKey, appSecret, accountNo }
  // accountNo = `${cano}-${acntPrdtCd}` 형태로 조합 후 전달
  await apiClient('/users/me/kis-keys', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/**
 * 한국투자증권 API 키 정보 수정
 * PATCH /api/v1/users/me/kis-keys
 *
 * - 변경할 필드만 포함해도 됨 (부분 업데이트)
 * - 연동 정보 없을 경우 404(USER_002) 반환 → registerKisKey() 먼저 호출 필요
 *
 * @param {{ appKey?: string, appSecret?: string, accountNo?: string }} payload
 *   변경하고 싶은 필드만 포함
 */
export async function updateKisKey(payload) {
  await apiClient('/users/me/kis-keys', {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
}

/**
 * 한국투자증권 API 연동 해제 (키 삭제)
 * DELETE /api/v1/users/me/kis-keys
 *
 * - 연동 해제 후 자동매매 및 자산 조회 기능 사용 불가
 * - 연동 정보 없을 경우 404(USER_002) 반환
 */
export async function deleteKisKey() {
  await apiClient('/users/me/kis-keys', {
    method: 'DELETE',
  });
}
/**
 * 내 정보 조회 (프로필 + KIS 연동 상태)
 * GET /api/v1/users/me
 */
export async function getMyInfo() {
  const data = await apiClient('/users/me');
  return data.data;
}
