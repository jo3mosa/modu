package com.modu.backend.domain.auth.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * application.yml의 jwt.* 설정값을 바인딩하는 프로퍼티 클래스
 *
 * jwt:
 *   secret: ${JWT_SECRET}
 *   access-token-expiration: 3600000    # 1시간 (ms)
 *   refresh-token-expiration: 1209600000 # 14일 (ms)
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secret;

    /** Access Token 만료 시간 (밀리초) */
    private long accessTokenExpiration;

    /** Refresh Token 만료 시간 (밀리초) */
    private long refreshTokenExpiration;
}
