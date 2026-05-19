package com.modu.backend.domain.market.feed;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 시세 Feed 동작 모드 + 점진 전환용 설정.
 *
 * - clientMode = LOCAL  : backend pod 가 직접 KIS WebSocket 보유 (현행 호환)
 * - clientMode = REMOTE : backend pod 가 Redis 경유로 gateway 에서 tick 수신 (목표 구조)
 *
 * 마이그레이션 동안 LOCAL → REMOTE 단방향 전환. 양쪽 동시 활성 금지.
 *
 * gateway 측은 본 설정과 무관 — 항상 KIS 직접 보유 + Redis publish.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "market.feed")
public class MarketFeedProperties {

    /** 시세 수신 모드 */
    private ClientMode clientMode = ClientMode.LOCAL;

    /** backend pod 식별자 (debug 용, KisControlMessage.podId 에 들어감). 기본은 hostname. */
    private String podId;

    public String resolvePodId() {
        if (podId != null && !podId.isBlank()) return podId;
        String hostname = System.getenv("HOSTNAME");
        return hostname != null && !hostname.isBlank() ? hostname : "unknown";
    }

    public enum ClientMode {
        /** 현 동작 — backend 가 KIS 직접 연결. 1 pod 환경에서만 안전. */
        LOCAL,
        /** 새 동작 — backend 가 Redis 경유로 tick 수신. replicas N개 안전. */
        REMOTE
    }
}
