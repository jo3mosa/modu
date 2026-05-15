package com.modu.backend.domain.market.cache;

import com.modu.backend.domain.market.dto.RealtimePriceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 실시간 시세 Redis 캐시 서비스 (S14P31B106-304)
 *
 * KIS WebSocket 으로 수신한 실시간 체결가를 종목별 키에 저장한다.
 * AI 에이전트 등 컴포넌트가 polling 방식으로 GET 하여 사용한다.
 *
 * [Redis 키 명세]
 *  - Key:   market:price:{stockCode}
 *  - Type:  String (숫자)
 *  - TTL:   없음 (영구) — WebSocket 재연결 시 자연 갱신되므로 별도 만료 불필요
 *  - 값 예: "71000"
 *
 * [실패 정책]
 *  - Redis SET 실패는 ERROR 로그만 — 호출자(broadcast 흐름) 에 영향 주지 않음
 *  - 시세 broadcast(프론트)는 시세 캐시와 독립적으로 진행되어야 함
 */
@Slf4j
@Component
public class RealtimePriceCacheService {

    private static final String KEY_PREFIX = "market:price:";

    private final StringRedisTemplate redisTemplate;

    public RealtimePriceCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 실시간 시세를 market:price:{stockCode} 키에 SET
     *
     * stockCode / currentPrice 가 null 이면 조용히 무시.
     * Redis 예외 발생 시 ERROR 로그만 출력, 예외는 전파하지 않음.
     */
    public void cache(RealtimePriceResponse price) {
        if (price == null || price.stockCode() == null || price.currentPrice() == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + price.stockCode(),
                    String.valueOf(price.currentPrice())
            );
        } catch (Exception e) {
            log.error("실시간 시세 Redis 저장 실패 - stockCode: {}, price: {}",
                    price.stockCode(), price.currentPrice(), e);
        }
    }
}
