package com.modu.backend.domain.auth.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * application.yml의 kakao.* 설정값을 바인딩하는 프로퍼티 클래스
 *
 * kakao:
 *   client-id: ${KAKAO_CLIENT_ID}       # REST API 키
 *   client-secret: ${KAKAO_CLIENT_SECRET}
 *   redirect-uri: ${KAKAO_REDIRECT_URI}  # 카카오 콘솔에 등록한 redirect URI와 정확히 일치해야 함
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kakao")
public class KakaoProperties {

    private String clientId;
    private String clientSecret;
    private String redirectUri;
}
