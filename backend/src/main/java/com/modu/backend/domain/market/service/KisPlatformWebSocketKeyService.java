package com.modu.backend.domain.market.service;

import com.modu.backend.domain.user.client.KisTokenClient;
import com.modu.backend.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 플랫폼 KIS WebSocket 승인키 관리 서비스
 *
 * 사용자별 KisTokenService와 분리된 플랫폼 공용 승인키 관리
 * - 체결가/호가 등 공개 시장 데이터용
 * - Redis "kis:platform:websocket-key" 캐시, TTL 23시간
 */
@Service
@RequiredArgsConstructor
public class KisPlatformWebSocketKeyService {

    private final KisTokenClient kisTokenClient;
    private final KisApiProperties kisApiProperties;

    /**
     * 플랫폼 KIS WebSocket 승인키 반환
     *
     * sync = true: TTL 만료 시 동시 다중 요청의 승인키 중복 발급 방지
     */
    @Cacheable(value = "kis:platform:websocket-key", key = "'global'", sync = true)
    public String getApprovalKey() {
        return kisTokenClient.issueWebSocketKey(
                kisApiProperties.getAppKey(),
                kisApiProperties.getAppSecret()
        );
    }
}
