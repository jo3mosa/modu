package com.modu.backend.domain.market.service;

import com.modu.backend.domain.user.client.KisTokenClient;
import com.modu.backend.global.config.KisApiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KisPlatformWebSocketKeyServiceTest {

    @Test
    @DisplayName("플랫폼 KIS 자격증명으로 WebSocket 승인키 발급")
    void getApprovalKey() {
        // given
        KisTokenClient kisTokenClient = mock(KisTokenClient.class);
        KisApiProperties kisApiProperties = new KisApiProperties();
        kisApiProperties.setAppKey("platform-app-key");
        kisApiProperties.setAppSecret("platform-app-secret");

        KisPlatformWebSocketKeyService service =
                new KisPlatformWebSocketKeyService(kisTokenClient, kisApiProperties);

        when(kisTokenClient.issueWebSocketKey("platform-app-key", "platform-app-secret"))
                .thenReturn("approval-key");

        // when
        String result = service.getApprovalKey();

        // then
        assertThat(result).isEqualTo("approval-key");
        verify(kisTokenClient).issueWebSocketKey("platform-app-key", "platform-app-secret");
    }
}

