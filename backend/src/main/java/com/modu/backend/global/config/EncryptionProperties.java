package com.modu.backend.global.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml의 encryption.* 설정값 바인딩
 *
 * encryption:
 *   kis-key: ${KIS_ENCRYPTION_KEY}  # AES-256-GCM용 64자리 HEX 문자열 (32바이트)
 *
 * 키 생성: openssl rand -hex 32
 * 예시: a3f1c2d4e5b6... (0-9, a-f 64자)
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "encryption")
public class EncryptionProperties {

    @NotBlank
    private String kisKey;
}
