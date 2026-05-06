package com.modu.backend.domain.market.service;

import com.modu.backend.domain.user.client.KisTokenClient;
import com.modu.backend.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KisPlatformWebSocketKeyService {

    private final KisTokenClient kisTokenClient;
    private final KisApiProperties kisApiProperties;

    @Cacheable(value = "kis:platform:websocket-key", key = "'global'", sync = true)
    public String getApprovalKey() {
        return kisTokenClient.issueWebSocketKey(
                kisApiProperties.getAppKey(),
                kisApiProperties.getAppSecret()
        );
    }
}

