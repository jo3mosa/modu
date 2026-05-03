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
 *   kis-key: ${KIS_ENCRYPTION_KEY}  # AES-256-GCM용 32바이트 Base64 키
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
