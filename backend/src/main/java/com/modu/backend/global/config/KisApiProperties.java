package com.modu.backend.global.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml의 kis.api.* 설정값 바인딩 (플랫폼 글로벌 KIS 자격증명)
 *
 * kis:
 *   api:
 *     app-key: ${KIS_APP_KEY}
 *     app-secret: ${KIS_APP_SECRET}
 *
 * URL은 kisRestClient 빈(RestClientConfig)에서 이미 base URL로 설정하므로 중복 바인딩 불필요
 * 용도: 종목 시세 등 공개 시장 데이터 조회 헤더에 appkey/appsecret 주입
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "kis.api")
public class KisApiProperties {

    @NotBlank
    private String appKey;

    @NotBlank
    private String appSecret;
}
