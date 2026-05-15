package com.modu.backend.global.kis;

import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * KIS API 호출 공통 템플릿
 *
 * KIS 가 EGW00202 (토큰 무효화) 를 응답한 경우만 액세스 토큰을 강제 재발급하고 1회 재시도.
 * 그 외 EXTERNAL_API_ERROR (네트워크/JSON 파싱/응답 검증 실패 등) 는 재시도 없이 전파 —
 * 비멱등 호출(주문/정정/취소) 의 중복 실행을 방지한다.
 *
 * [기존 패턴]
 *   String token = kisTokenService.getOrIssueAccessToken(userId, appKey, appSecret);
 *   return kisXxxClient.someMethod(token, appKey, appSecret, ...);
 *
 * [템플릿 적용 후]
 *   return template.callWithTokenRetry(userId, appKey, appSecret,
 *       token -> kisXxxClient.someMethod(token, appKey, appSecret, ...));
 *
 * [전제 조건]
 *   - KIS 클라이언트는 KIS 응답에서 msg_cd == "EGW00202" 를 식별하면
 *     ApiException(UserErrorCode.KIS_TOKEN_INVALIDATED) 를 던져야 함
 *   - 일반 KIS 비즈니스 에러나 transport 실패는 EXTERNAL_API_ERROR 로 던져 재시도 대상에서 제외됨
 *
 * [재시도 정책]
 *   - KIS_TOKEN_INVALIDATED 1회만 재시도 (rate limit / 영구 실패 시 폭주 차단)
 *   - 재시도 자체 실패는 호출자에게 그대로 전파 — caller 가 도메인별 에러 매핑 결정
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisApiCallTemplate {

    private final KisTokenService kisTokenService;

    /**
     * 결과 반환 KIS 호출 — 토큰 무효화(EGW00202) 시 강제 재발급 후 1회 재시도
     */
    public <T> T callWithTokenRetry(Long userId, String appKey, String appSecret,
                                    Function<String, T> kisCall) {
        String token = kisTokenService.getOrIssueAccessToken(userId, appKey, appSecret);
        try {
            return kisCall.apply(token);
        } catch (ApiException e) {
            if (!isTokenInvalidated(e)) throw e;
            log.warn("KIS 토큰 무효화 감지(EGW00202) → 강제 재발급 후 1회 재시도 - userId: {}", userId);
            String fresh = kisTokenService.issueAndSaveAccessToken(userId, appKey, appSecret);
            return kisCall.apply(fresh);
        }
    }

    /**
     * 토큰 무효화 식별 — KIS 클라이언트가 EGW00202 응답 시 던지는 에러 코드만 정밀 매칭.
     *
     * EXTERNAL_API_ERROR 는 의도적으로 제외 — 네트워크 timeout 이나 JSON 파싱 실패에서
     * 비멱등 호출(주문 등) 을 재시도하면 중복 실행 위험이 있기 때문.
     */
    private boolean isTokenInvalidated(ApiException e) {
        return UserErrorCode.KIS_TOKEN_INVALIDATED.equals(e.getErrorCode());
    }
}
