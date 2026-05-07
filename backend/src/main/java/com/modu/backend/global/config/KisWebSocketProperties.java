package com.modu.backend.global.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;

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

    @Min(100)
    private long connectionTimeoutMs = 5000;

    private List<String> allowedOriginPatterns = List.of("http://localhost:3000", "http://localhost:5173");
}
