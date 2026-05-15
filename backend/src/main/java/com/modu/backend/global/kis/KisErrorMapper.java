package com.modu.backend.global.kis;

import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;

/**
 * KIS 응답 코드 → ApiException 매핑 유틸
 *
 * KIS 클라이언트에서 응답 검증 실패 시 호출. msg_cd 를 정밀 식별해 적절한 도메인 에러로 변환,
 * KisApiCallTemplate 가 토큰 무효화에만 자동 재시도하도록 신호한다.
 *
 * [매핑 규칙]
 *   - "EGW00202" (외부에서 토큰 갱신되어 캐시된 토큰 무효화) → KIS_TOKEN_INVALIDATED
 *   - 그 외 / null                                              → EXTERNAL_API_ERROR
 *
 * [확장 가이드]
 *   추후 EGW00133 (rate limit) 등 별도 처리가 필요한 KIS 응답코드는 본 매퍼에 추가.
 */
public final class KisErrorMapper {

    /** KIS 토큰 무효화 — 외부에서 새 토큰이 발급돼 직전 토큰이 무효화됐을 때 */
    public static final String EGW_TOKEN_INVALIDATED = "EGW00202";

    private KisErrorMapper() {}

    /**
     * KIS 응답 msg_cd 기준으로 적절한 ApiException 생성.
     *
     * @param msgCd KIS 응답의 msg_cd 필드 (null 가능)
     */
    public static ApiException toApiException(String msgCd) {
        if (EGW_TOKEN_INVALIDATED.equals(msgCd)) {
            return new ApiException(UserErrorCode.KIS_TOKEN_INVALIDATED);
        }
        return new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
    }
}
