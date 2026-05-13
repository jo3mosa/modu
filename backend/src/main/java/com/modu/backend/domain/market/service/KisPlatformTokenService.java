package com.modu.backend.domain.market.service;

import com.modu.backend.domain.user.client.KisTokenClient;
import com.modu.backend.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 플랫폼 글로벌 KIS 액세스 토큰 관리 서비스
 *
 * 사용자별 KisTokenService와 달리 플랫폼 공용 자격증명(KIS_APP_KEY, KIS_APP_SECRET)으로 발급
 * 종목 시세 등 공개 시장 데이터 조회에 사용
 *
 * [캐싱]
 * - Redis "kis:platform:token" 캐시, TTL 23시간 (KIS 토큰 유효기간 24시간 기준)
 * - 서버 재시작 시 캐시 초기화 (.env 변경 등 appKey 교체 대응)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KisPlatformTokenService {

    private final KisTokenClient kisTokenClient;
    private final KisApiProperties kisApiProperties;

    /** 서버 시작 시 이전 appKey로 발급된 stale 토큰 제거 */
    @CacheEvict(value = "kis:platform:token", allEntries = true)
    @EventListener(ApplicationReadyEvent.class)
    public void evictOnStartup() {
        log.info("[플랫폼토큰] 서버 시작 - 캐시 초기화 (appKey: {}...)", kisApiProperties.getAppKey().substring(0, 8));
    }

    /**
     * 플랫폼 KIS 액세스 토큰 반환 (캐시 우선, 만료 시 재발급)
     *
     * sync = true: TTL 만료 시 동시 다중 요청이 들어와도 단 한 번만 issueAccessToken() 호출
     */
    @Cacheable(value = "kis:platform:token", key = "'global'", sync = true)
    public String getAccessToken() {
        log.info("[플랫폼토큰] 신규 발급 (appKey: {}...)", kisApiProperties.getAppKey().substring(0, 8));
        return kisTokenClient.issueAccessToken(
                kisApiProperties.getAppKey(),
                kisApiProperties.getAppSecret()
        );
    }
}
