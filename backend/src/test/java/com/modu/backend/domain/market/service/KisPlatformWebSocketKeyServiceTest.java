package com.modu.backend.domain.market.service;

import com.modu.backend.domain.user.client.KisTokenClient;
import com.modu.backend.global.config.KisApiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(KisPlatformWebSocketKeyServiceTest.TestConfig.class)
class KisPlatformWebSocketKeyServiceTest {

    @Autowired
    private KisPlatformWebSocketKeyService service;

    @Autowired
    private KisTokenClient kisTokenClient;

    @BeforeEach
    void setUp() {
        reset(kisTokenClient);
    }

    @Test
    @DisplayName("플랫폼 KIS WebSocket 승인키를 캐시한다")
    void getApprovalKey() {
        // given
        when(kisTokenClient.issueWebSocketKey("platform-app-key", "platform-app-secret"))
                .thenReturn("approval-key");

        // when
        String first = service.getApprovalKey();
        String second = service.getApprovalKey();

        // then
        assertThat(first).isEqualTo("approval-key");
        assertThat(second).isEqualTo("approval-key");
        verify(kisTokenClient, times(1)).issueWebSocketKey("platform-app-key", "platform-app-secret");
    }

    @Configuration
    @EnableCaching
    @Import(KisPlatformWebSocketKeyService.class)
    static class TestConfig {

        @Bean
        KisTokenClient kisTokenClient() {
            return mock(KisTokenClient.class);
        }

        @Bean
        KisApiProperties kisApiProperties() {
            KisApiProperties properties = new KisApiProperties();
            properties.setAppKey("platform-app-key");
            properties.setAppSecret("platform-app-secret");
            return properties;
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("kis:platform:websocket-key");
        }
    }
}
