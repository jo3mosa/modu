package com.modu.backend.domain.user.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.global.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * KIS OAuth 액세스 토큰 발급 클라이언트
 *
 * POST /oauth2/tokenP 호출
 * - appKey, appSecret으로 Bearer 토큰 발급
 * - 토큰 유효기간: 1일 (86400초)
 * - 발급 실패 시 KIS_TOKEN_ISSUANCE_FAILED 예외 (자격증명 오류 또는 KIS 서버 오류)
 */
@Slf4j
@Component
public class KisTokenClient {

    private static final String TOKEN_PATH = "/oauth2/tokenP";

    private final RestClient kisRestClient;

    public KisTokenClient(RestClient kisRestClient) {
        this.kisRestClient = kisRestClient;
    }

    /**
     * KIS 액세스 토큰 발급
     *
     * @param appKey    사용자 KIS App Key (복호화된 원문)
     * @param appSecret 사용자 KIS App Secret (복호화된 원문)
     * @return 발급된 액세스 토큰
     */
    public String issueAccessToken(String appKey, String appSecret) {
        try {
            TokenResponse response = kisRestClient.post()
                    .uri(TOKEN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TokenRequest("client_credentials", appKey, appSecret))
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.accessToken() == null) {
                throw new ApiException(UserErrorCode.KIS_TOKEN_ISSUANCE_FAILED);
            }

            return response.accessToken();

        } catch (RestClientException e) {
            log.error("KIS 액세스 토큰 발급 실패: {}", e.getMessage());
            throw new ApiException(UserErrorCode.KIS_TOKEN_ISSUANCE_FAILED);
        }
    }

    /**
     * KIS 실시간 웹소켓 접속키 발급
     *
     * POST /oauth2/approval 호출
     * - 유효기간 24시간
     * - 웹소켓 연결 시 appkey/appsecret 대신 헤더에 사용
     */
    public String issueWebSocketKey(String appKey, String appSecret) {
        try {
            WebSocketKeyResponse response = kisRestClient.post()
                    .uri("/oauth2/approval")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TokenRequest("client_credentials", appKey, appSecret))
                    .retrieve()
                    .body(WebSocketKeyResponse.class);

            if (response == null || response.approvalKey() == null) {
                throw new ApiException(UserErrorCode.KIS_TOKEN_ISSUANCE_FAILED);
            }

            return response.approvalKey();

        } catch (RestClientException e) {
            log.error("KIS 웹소켓 접속키 발급 실패: {}", e.getMessage());
            throw new ApiException(UserErrorCode.KIS_TOKEN_ISSUANCE_FAILED);
        }
    }

    private record TokenRequest(
            @JsonProperty("grant_type") String grantType,
            String appkey,
            String appsecret
    ) {}

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn
    ) {}

    private record WebSocketKeyResponse(
            @JsonProperty("approval_key") String approvalKey
    ) {}
}
