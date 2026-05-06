package com.modu.backend.domain.market.service;

import com.modu.backend.domain.user.client.KisTokenClient;
import com.modu.backend.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 플랫폼 글로벌 KIS 액세스 토큰 관리 서비스
 *
 * 사용자별 KisTokenService와 달리 플랫폼 공용 자격증명(KIS_APP_KEY, KIS_APP_SECRET)으로 발급
 * 종목 시세 등 공개 시장 데이터 조회에 사용
 *
 * [캐싱]
 * - Redis "kis:platform:token" 캐시, TTL 23시간 (KIS 토큰 유효기간 24시간 기준)
 * - 만료 시 다음 요청에서 자동 재발급
 */
@Service
@RequiredArgsConstructor
public class KisPlatformTokenService {

    private final KisTokenClient kisTokenClient;
    private final KisApiProperties kisApiProperties;

    /**
     * 플랫폼 KIS 액세스 토큰 반환 (캐시 우선, 만료 시 재발급)
     *
     * sync = true: TTL 만료 시 동시 다중 요청이 들어와도 단 한 번만 issueAccessToken() 호출
     * (cache stampede 방지)
     */
    @Cacheable(value = "kis:platform:token", key = "'global'", sync = true)
    public String getAccessToken() {
        return kisTokenClient.issueAccessToken(
                kisApiProperties.getAppKey(),
                kisApiProperties.getAppSecret()
        );
    }
}
