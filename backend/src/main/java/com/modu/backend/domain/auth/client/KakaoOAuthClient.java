package com.modu.backend.domain.auth.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.domain.auth.exception.AuthErrorCode;
import com.modu.backend.global.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 OAuth API 호출 클라이언트
 *
 * 외부에서는 getUserInfo(code)만 호출,
 * 내부적으로 2단계 API 호출(토큰 발급 → 사용자 정보 조회) 처리
 *
 * [호출 흐름]
 * 1. code → POST kauth.kakao.com/oauth/token → accessToken
 * 2. accessToken → GET kapi.kakao.com/v2/user/me → KakaoUserInfo
 */
@Slf4j
@Component
public class KakaoOAuthClient {

    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String USER_INFO_URL = "https://kapi.kakao.com/v2/user/me";

    private final RestClient restClient;
    private final KakaoProperties kakaoProperties;

    public KakaoOAuthClient(KakaoProperties kakaoProperties, RestClient kakaoRestClient) {
        this.kakaoProperties = kakaoProperties;
        this.restClient = kakaoRestClient;
    }

    /**
     * 인가 코드로 카카오 사용자 정보 조회
     * 토큰 발급과 사용자 정보 조회를 순차 처리
     */
    public KakaoUserInfo getUserInfo(String code) {
        String accessToken = fetchAccessToken(code);
        return fetchUserInfo(accessToken);
    }

    // ── 1단계: 인가 코드 → 카카오 액세스 토큰 ────────────────────────────────────

    private String fetchAccessToken(String code) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", kakaoProperties.getClientId());
        params.add("redirect_uri", kakaoProperties.getRedirectUri());
        params.add("code", code);
        params.add("client_secret", kakaoProperties.getClientSecret());

        try {
            TokenResponse response = restClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.accessToken() == null) {
                throw new ApiException(AuthErrorCode.KAKAO_TOKEN_FETCH_FAILED);
            }
            return response.accessToken();

        } catch (RestClientException e) {
            log.error("카카오 액세스 토큰 발급 실패: {}", e.getMessage());
            throw new ApiException(AuthErrorCode.KAKAO_TOKEN_FETCH_FAILED);
        }
    }

    // ── 2단계: 카카오 액세스 토큰 → 사용자 정보 ─────────────────────────────────

    private KakaoUserInfo fetchUserInfo(String accessToken) {
        try {
            UserInfoResponse response = restClient.get()
                    .uri(USER_INFO_URL)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(UserInfoResponse.class);

            if (response == null || response.id() == null) {
                throw new ApiException(AuthErrorCode.KAKAO_USER_INFO_FAILED);
            }

            return new KakaoUserInfo(
                    response.id().toString(),
                    response.nickname(),
                    response.email()
            );

        } catch (RestClientException e) {
            log.error("카카오 사용자 정보 조회 실패: {}", e.getMessage());
            throw new ApiException(AuthErrorCode.KAKAO_USER_INFO_FAILED);
        }
    }

    // ── 카카오 API 응답 파싱용 내부 레코드 ────────────────────────────────────────

    /** 카카오 토큰 발급 API 응답 */
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken
    ) {}

    /**
     * 카카오 사용자 정보 API 응답
     * 중첩 구조(kakao_account → profile)를 내부 레코드로 표현
     */
    private record UserInfoResponse(
            Long id,
            @JsonProperty("kakao_account") KakaoAccount kakaoAccount
    ) {
        String nickname() {
            if (kakaoAccount == null || kakaoAccount.profile() == null) return null;
            return kakaoAccount.profile().nickname();
        }

        String email() {
            if (kakaoAccount == null) return null;
            return kakaoAccount.email();
        }

        private record KakaoAccount(
                String email,
                Profile profile
        ) {}

        private record Profile(
                String nickname
        ) {}
    }
}
