package com.modu.backend.domain.market.websocket;

import com.modu.backend.domain.market.dto.OrderbookResponse;
import com.modu.backend.domain.market.dto.RealtimePriceResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class KisRealtimeMessageParser {

    public Optional<KisRealtimeParsedMessage> parse(String message) {
        if (message == null || message.isBlank() || (!message.startsWith("0|") && !message.startsWith("1|"))) {
            return Optional.empty();
        }

        String[] frame = message.split("\\|", 4);
        if (frame.length < 4) {
            return Optional.empty();
        }

        KisRealtimeStreamType type = KisRealtimeStreamType.fromTrId(frame[1]);
        String[] fields = frame[3].split("\\^", -1);
        Object payload = switch (type) {
            case PRICE -> parsePrice(fields);
            case ORDERBOOK -> parseOrderbook(fields);
        };
        String stockCode = fields.length > 0 ? fields[0] : "";

        return Optional.of(new KisRealtimeParsedMessage(new KisRealtimeStreamKey(type, stockCode), payload));
    }

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

    private String value(String[] fields, int index) {
        if (index >= fields.length) {
            return null;
        }
        return fields[index].isBlank() ? null : fields[index];
    }

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

    public record KisRealtimeParsedMessage(
            KisRealtimeStreamKey key,
            Object payload
    ) {
    }
}

