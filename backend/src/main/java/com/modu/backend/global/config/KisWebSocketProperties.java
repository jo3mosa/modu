package com.modu.backend.global.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * KIS WebSocket 연결 설정
 *
 * [설정 항목]
 * - url: KIS 실시간 WebSocket 접속 URL
 * - reconnectMaxAttempts: upstream 연결 종료 시 재연결 최대 횟수
 * - reconnectDelayMs: 재연결 시도 간 대기 시간(ms)
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "kis.websocket")
public class KisWebSocketProperties {

    @NotBlank
    private String url;

    @Min(1)
    private int reconnectMaxAttempts = 3;

    @Min(100)
    private long reconnectDelayMs = 1000;

    /** KIS WebSocket 연결 타임아웃 (ms). 초과 시 TimeoutException 발생 */
    @Min(1000)
    private long connectTimeoutMs = 5000;

    /** 프론트 WebSocket 허용 Origin 목록. 로컬: * / 운영: 실제 도메인 */
    private String allowedOrigins = "*";
}
