package com.modu.backend.domain.trading.kafka.producer;

import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.TradeOrderExecutedMessage;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeOrderProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Signal Handler, Position Monitor, REST API에서 호출
    public void publishOrderSubmitted(TradeOrderMessage message) {
        kafkaTemplate.send(KafkaTopic.TRADE_ORDER_SUBMITTED,
            message.orderId(),
            message);
        log.info("주문 발행: orderId={}, userId={}, source={}",
            message.orderId(), message.userId(), message.source());
    }

    // KIS WebSocket 핸들러에서 호출
    public void publishOrderExecuted(TradeOrderExecutedMessage message) {
        kafkaTemplate.send(KafkaTopic.TRADE_ORDER_EXECUTED,
            String.valueOf(message.orderId()),
            message);
        log.info("체결 발행: orderId={}, stockCode={}, isFinalFill={}",
            message.orderId(), message.stockCode(), message.isFinalFill());
    }
}
