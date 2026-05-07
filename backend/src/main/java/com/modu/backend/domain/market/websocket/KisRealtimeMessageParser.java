package com.modu.backend.domain.market.websocket;

import com.modu.backend.domain.market.dto.OrderbookResponse;
import com.modu.backend.domain.market.dto.RealtimePriceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * KIS 실시간 WebSocket 메시지 파서
 *
 * [수신 포맷]
 * - 시스템 메시지: JSON
 * - 실시간 데이터: 0|H0STCNT0|1|필드^필드^...
 *
 * [변환 대상]
 * - H0STCNT0: RealtimePriceResponse
 * - H0STASP0: OrderbookResponse
 */
@Slf4j
@Component
public class KisRealtimeMessageParser {

    /**
     * delimiter 기반 실시간 데이터 파싱
     */
    public Optional<KisRealtimeParsedMessage> parse(String message) {
        if (message == null || message.isBlank() || (!message.startsWith("0|") && !message.startsWith("1|"))) {
            return Optional.empty();
        }

        String[] frame = message.split("\\|", 4);
        if (frame.length < 4) {
            return Optional.empty();
        }

        KisRealtimeStreamType type;
        try {
            type = KisRealtimeStreamType.fromTrId(frame[1]);
        } catch (IllegalArgumentException e) {
            // KIS에서 새 TR ID 추가 또는 예상치 못한 메시지 수신 시 메시지 손실 방지
            log.warn("지원하지 않는 KIS TR ID 수신, 무시 - trId: {}", frame[1]);
            return Optional.empty();
        }
        String[] fields = frame[3].split("\\^", -1);
        Object payload = switch (type) {
            case PRICE -> parsePrice(fields);
            case ORDERBOOK -> parseOrderbook(fields);
        };
        String stockCode = fields.length > 0 ? fields[0] : "";

        return Optional.of(new KisRealtimeParsedMessage(new KisRealtimeStreamKey(type, stockCode), payload));
    }

    /**
     * H0STCNT0 체결가 필드 매핑
     */
    private RealtimePriceResponse parsePrice(String[] f) {
        return new RealtimePriceResponse(
                value(f, 0),
                value(f, 1),
                longValue(f, 2),
                value(f, 3),
                longValue(f, 4),
                doubleValue(f, 5),
                longValue(f, 7),
                longValue(f, 8),
                longValue(f, 9),
                longValue(f, 10),
                longValue(f, 11),
                longValue(f, 12),
                longValue(f, 13),
                longValue(f, 14),
                doubleValue(f, 18),
                longValue(f, 39),
                longValue(f, 40),
                longValue(f, 46)
        );
    }

    /**
     * H0STASP0 호가 필드 매핑
     */
    private OrderbookResponse parseOrderbook(String[] f) {
        List<OrderbookResponse.OrderbookLevel> asks = new ArrayList<>();
        List<OrderbookResponse.OrderbookLevel> bids = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            asks.add(new OrderbookResponse.OrderbookLevel(i + 1, longValue(f, 3 + i), longValue(f, 23 + i)));
            bids.add(new OrderbookResponse.OrderbookLevel(i + 1, longValue(f, 13 + i), longValue(f, 33 + i)));
        }

        return new OrderbookResponse(
                value(f, 0),
                value(f, 1),
                asks,
                bids,
                longValue(f, 43),
                longValue(f, 44),
                longValue(f, 47),
                longValue(f, 48),
                longValue(f, 53)
        );
    }

    /**
     * 문자열 필드 안전 조회
     */
    private String value(String[] fields, int index) {
        if (index >= fields.length) {
            return null;
        }
        return fields[index].isBlank() ? null : fields[index];
    }

    /**
     * Long 필드 안전 변환
     */
    private Long longValue(String[] fields, int index) {
        String value = value(fields, index);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Double 필드 안전 변환
     */
    private Double doubleValue(String[] fields, int index) {
        String value = value(fields, index);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 파싱 결과와 fan-out 대상 키
     */
    public record KisRealtimeParsedMessage(
            KisRealtimeStreamKey key,
            Object payload
    ) {
    }
}
