package com.modu.backend.domain.market.websocket;

import com.modu.backend.domain.market.dto.OrderbookResponse;
import com.modu.backend.domain.market.dto.RealtimePriceResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KisRealtimeMessageParserTest {

    private final KisRealtimeMessageParser parser = new KisRealtimeMessageParser();

    @Test
    @DisplayName("H0STCNT0 실시간 체결가 메시지를 RealtimePriceResponse로 변환")
    void parsePriceMessage() {
        // given
        String[] fields = fields(47);
        fields[0] = "005930";
        fields[1] = "093001";
        fields[2] = "71200";
        fields[3] = "2";
        fields[4] = "1200";
        fields[5] = "1.71";
        fields[7] = "70000";
        fields[8] = "71500";
        fields[9] = "69900";
        fields[10] = "71300";
        fields[11] = "71200";
        fields[12] = "100";
        fields[13] = "1500000";
        fields[14] = "106800000000";
        fields[18] = "128.45";
        fields[39] = "500000";
        fields[40] = "450000";
        fields[46] = "71000";

        String message = realtimeMessage("H0STCNT0", fields);

        // when
        Optional<KisRealtimeMessageParser.KisRealtimeParsedMessage> result = parser.parse(message);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().key()).isEqualTo(new KisRealtimeStreamKey(KisRealtimeStreamType.PRICE, "005930"));
        assertThat(result.get().payload()).isInstanceOf(RealtimePriceResponse.class);

        RealtimePriceResponse payload = (RealtimePriceResponse) result.get().payload();
        assertThat(payload.stockCode()).isEqualTo("005930");
        assertThat(payload.tradeTime()).isEqualTo("093001");
        assertThat(payload.currentPrice()).isEqualTo(71200L);
        assertThat(payload.priceChangeRate()).isEqualTo(1.71);
        assertThat(payload.totalAskQuantity()).isEqualTo(500000L);
        assertThat(payload.viStandardPrice()).isEqualTo(71000L);
    }

    @Test
    @DisplayName("H0STASP0 실시간 호가 메시지를 OrderbookResponse로 변환")
    void parseOrderbookMessage() {
        // given
        String[] fields = fields(54);
        fields[0] = "005930";
        fields[1] = "093001";
        fields[3] = "71300";
        fields[4] = "71400";
        fields[13] = "71200";
        fields[14] = "71100";
        fields[23] = "1000";
        fields[24] = "2000";
        fields[33] = "1500";
        fields[34] = "2500";
        fields[43] = "500000";
        fields[44] = "450000";
        fields[47] = "71200";
        fields[48] = "300";
        fields[53] = "1500000";

        String message = realtimeMessage("H0STASP0", fields);

        // when
        Optional<KisRealtimeMessageParser.KisRealtimeParsedMessage> result = parser.parse(message);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().key()).isEqualTo(new KisRealtimeStreamKey(KisRealtimeStreamType.ORDERBOOK, "005930"));
        assertThat(result.get().payload()).isInstanceOf(OrderbookResponse.class);

        OrderbookResponse payload = (OrderbookResponse) result.get().payload();
        assertThat(payload.stockCode()).isEqualTo("005930");
        assertThat(payload.asks()).hasSize(10);
        assertThat(payload.bids()).hasSize(10);
        assertThat(payload.asks().get(0).price()).isEqualTo(71300L);
        assertThat(payload.asks().get(0).quantity()).isEqualTo(1000L);
        assertThat(payload.bids().get(0).price()).isEqualTo(71200L);
        assertThat(payload.bids().get(0).quantity()).isEqualTo(1500L);
        assertThat(payload.totalAskQuantity()).isEqualTo(500000L);
    }

    @Test
    @DisplayName("JSON 시스템 메시지는 파싱하지 않음")
    void ignoreSystemMessage() {
        // when
        Optional<KisRealtimeMessageParser.KisRealtimeParsedMessage> result = parser.parse("{\"header\":{\"tr_id\":\"PINGPONG\"}}");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("미지원 TR ID 수신 시 Optional.empty() 반환 (메시지 손실 방지)")
    void unknownTrIdReturnsEmpty() {
        // given - KIS에서 새로 추가된 TR ID가 수신된 상황
        String message = "0|H0STXXX|1|005930^093001^71200";

        // when
        Optional<KisRealtimeMessageParser.KisRealtimeParsedMessage> result = parser.parse(message);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("숫자 변환 실패 필드는 null로 처리")
    void invalidNumberReturnsNull() {
        // given
        String[] fields = fields(47);
        fields[0] = "005930";
        fields[2] = "invalid";

        // when
        Optional<KisRealtimeMessageParser.KisRealtimeParsedMessage> result =
                parser.parse(realtimeMessage("H0STCNT0", fields));

        // then
        RealtimePriceResponse payload = (RealtimePriceResponse) result.get().payload();
        assertThat(payload.currentPrice()).isNull();
    }

    @Test
    @DisplayName("미지원 TR ID 메시지는 무시한다")
    void ignoreUnsupportedTrId() {
        // given
        String[] fields = fields(47);
        fields[0] = "005930";

        // when
        Optional<KisRealtimeMessageParser.KisRealtimeParsedMessage> result =
                parser.parse(realtimeMessage("UNKNOWN", fields));

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("종목코드가 비어 있는 메시지는 무시한다")
    void ignoreBlankStockCode() {
        // given
        String[] fields = fields(47);
        fields[0] = " ";

        // when
        Optional<KisRealtimeMessageParser.KisRealtimeParsedMessage> result =
                parser.parse(realtimeMessage("H0STCNT0", fields));

        // then
        assertThat(result).isEmpty();
    }

    private String[] fields(int size) {
        String[] fields = new String[size];
        Arrays.fill(fields, "");
        return fields;
    }

    private String realtimeMessage(String trId, String[] fields) {
        return "0|" + trId + "|1|" + String.join("^", fields);
    }
}

