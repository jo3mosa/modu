package com.modu.backend.domain.market.feed;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * gateway → backend 시세 tick 메시지 — Redis 채널 {@link KisFeedChannels#tickUserChannel(long)} 로 publish.
 *
 * 시세(H0STCNT0) / 호가(H0STASP0) 두 종류의 payload 를 단일 envelope 에 담아 multiplex.
 * 수신 측은 {@code trId} 로 분기해서 RealtimePriceResponse / OrderbookResponse 로 변환.
 *
 * @param trId      KIS TR ID — 수신 측 type discrimination 용
 * @param userId    이 tick 이 속한 사용자 (debug/감사용)
 * @param stockCode 종목코드
 * @param payload   파싱된 도메인 객체 JSON (RealtimePriceResponse | OrderbookResponse)
 * @param ts        gateway 의 발신 시각 (epoch ms)
 */
public record KisTickEnvelope(
        String trId,
        long userId,
        String stockCode,
        JsonNode payload,
        long ts
) {

    @JsonCreator
    public KisTickEnvelope(
            @JsonProperty("trId") String trId,
            @JsonProperty("userId") long userId,
            @JsonProperty("stockCode") String stockCode,
            @JsonProperty("payload") JsonNode payload,
            @JsonProperty("ts") long ts
    ) {
        this.trId = trId;
        this.userId = userId;
        this.stockCode = stockCode;
        this.payload = payload;
        this.ts = ts;
    }
}
