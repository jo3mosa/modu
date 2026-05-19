package com.modu.backend.domain.market.feed;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * backend → gateway 제어 메시지 — Redis 채널 {@link KisFeedChannels#CONTROL} 로 publish.
 *
 * gateway 가 해당 userId 의 KIS WebSocket 세션에 시세/호가 SUBSCRIBE/UNSUBSCRIBE 를 위임 발신.
 *
 * @param op       SUBSCRIBE / UNSUBSCRIBE
 * @param userId   대상 사용자 (사용자 키 세션 식별자)
 * @param trId     KIS TR ID (H0STCNT0=체결가, H0STASP0=호가)
 * @param trKey    종목코드 6자리
 * @param podId    요청한 backend pod 식별자 (디버깅/추적용)
 * @param ts       요청 시각 (epoch ms)
 */
public record KisControlMessage(
        Op op,
        long userId,
        String trId,
        String trKey,
        String podId,
        long ts
) {

    @JsonCreator
    public KisControlMessage(
            @JsonProperty("op") Op op,
            @JsonProperty("userId") long userId,
            @JsonProperty("trId") String trId,
            @JsonProperty("trKey") String trKey,
            @JsonProperty("podId") String podId,
            @JsonProperty("ts") long ts
    ) {
        this.op = op;
        this.userId = userId;
        this.trId = trId;
        this.trKey = trKey;
        this.podId = podId;
        this.ts = ts;
    }

    public static KisControlMessage subscribe(long userId, String trId, String trKey, String podId) {
        return new KisControlMessage(Op.SUBSCRIBE, userId, trId, trKey, podId, System.currentTimeMillis());
    }

    public static KisControlMessage unsubscribe(long userId, String trId, String trKey, String podId) {
        return new KisControlMessage(Op.UNSUBSCRIBE, userId, trId, trKey, podId, System.currentTimeMillis());
    }

    public enum Op {
        SUBSCRIBE,
        UNSUBSCRIBE
    }
}
