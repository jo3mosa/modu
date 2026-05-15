package com.modu.backend.global.kis;

import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * KIS API 호출 공통 템플릿
 *
 * 토큰 무효화(EGW00202 등 EXTERNAL_API_ERROR) 감지 시 액세스 토큰을 강제 재발급하고
 * 1회 재시도. DB/Redis 캐시는 유효해 보이지만 KIS 서버측에서 무효화한 stale-valid
 * 케이스를 자동 복구한다.
 *
 * [기존 패턴]
 *   String token = kisTokenService.getOrIssueAccessToken(userId, appKey, appSecret);
 *   return kisXxxClient.someMethod(token, appKey, appSecret, ...);
 *
 * [템플릿 적용 후]
 *   return template.callWithTokenRetry(userId, appKey, appSecret,
 *       token -> kisXxxClient.someMethod(token, appKey, appSecret, ...));
 *
 * [재시도 정책]
 *   - 1회만 재시도 (rate limit / 영구 실패 시 무한 폭주 차단)
 *   - 재시도 자체 실패는 호출자에게 그대로 전파 — caller 가 도메인별 에러 매핑 결정
 *
 * [기존 KisOrderConsumer 의 EGW00202 retry 로직을 일반화한 것]
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisApiCallTemplate {

    private final KisTokenService kisTokenService;

    /**
     * 결과 반환 KIS 호출 — 토큰 무효화 시 강제 재발급 후 1회 재시도
     */
    public <T> T callWithTokenRetry(Long userId, String appKey, String appSecret,
                                    Function<String, T> kisCall) {
        String token = kisTokenService.getOrIssueAccessToken(userId, appKey, appSecret);
        try {
            return kisCall.apply(token);
        } catch (ApiException e) {
            if (!isTokenInvalidationCandidate(e)) throw e;
            log.warn("KIS 토큰 무효화 감지 → 강제 재발급 후 1회 재시도 - userId: {}", userId);
            String fresh = kisTokenService.issueAndSaveAccessToken(userId, appKey, appSecret);
            return kisCall.apply(fresh);
        }
    }

    /**
     * 토큰 무효화 후보 식별
     *
     * 현재는 KIS 호출 측이 ApiException(EXTERNAL_API_ERROR) 로 단일 수렴시키고 있어 이 코드로 판정.
     * 추후 EGW00202 등 KIS 응답코드를 별도 ErrorCode 로 분리하면 여기서 정밀화 가능.
     */
    private boolean isTokenInvalidationCandidate(ApiException e) {
        return CommonErrorCode.EXTERNAL_API_ERROR.equals(e.getErrorCode());
    }
}
