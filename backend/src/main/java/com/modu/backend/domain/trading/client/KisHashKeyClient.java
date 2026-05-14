package com.modu.backend.domain.trading.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * KIS HashKey 발급 클라이언트
 *
 * POST /uapi/hashkey
 * 주문 등 POST 요청 시 필요한 hashkey를 발급한다.
 * 주문 요청 바디를 그대로 전달하면 HASH 값을 반환하며, 이를 주문 헤더에 포함시킨다.
 */
@Slf4j
@Component
public class KisHashKeyClient {

    private static final String HASHKEY_PATH = "/uapi/hashkey";

    private final RestClient kisRestClient;

    public KisHashKeyClient(RestClient kisRestClient) {
        this.kisRestClient = kisRestClient;
    }

    public String getHashKey(String appKey, String appSecret, Map<String, String> body) {
        try {
            HashKeyResponse response = kisRestClient.post()
                    .uri(HASHKEY_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .body(body)
                    .retrieve()
                    .body(HashKeyResponse.class);

            if (response == null || response.hash() == null) {
                log.error("KIS HashKey 발급 실패 - 응답이 null");
                throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
            }

            log.info("[HashKey] 발급 성공 - prefix: {}", response.hash().substring(0, Math.min(20, response.hash().length())));
            return response.hash();

        } catch (RestClientException e) {
            log.error("KIS HashKey API 호출 실패: {}", e.getMessage());
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR);
        }
    }

    private record HashKeyResponse(
            @JsonProperty("HASH") String hash
    ) {}
}
