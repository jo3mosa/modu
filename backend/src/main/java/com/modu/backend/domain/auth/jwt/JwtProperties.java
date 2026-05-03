package com.modu.backend.domain.auth.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml의 jwt.* 설정값을 바인딩하는 프로퍼티 클래스
 *
 * jwt:
 *   secret: ${JWT_SECRET}
 *   access-token-expiration: 3600000    # 1시간 (ms)
 *   refresh-token-expiration: 1209600000 # 14일 (ms)
 *
 * @Validated: 필수 설정값 누락 또는 0 이하 값 입력 시 서버 시작 시점에 즉시 실패
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    @NotBlank
    private String secret;

    /** Access Token 만료 시간 (밀리초) */
    @Positive
    private long accessTokenExpiration;

    /** Refresh Token 만료 시간 (밀리초) */
    @Positive
    private long refreshTokenExpiration;
}
