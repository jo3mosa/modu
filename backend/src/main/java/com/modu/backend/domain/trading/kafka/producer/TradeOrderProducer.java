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

    /**
     * 주문 접수 이벤트 발행 (동기)
     *
     * .get()으로 브로커 수신 확인을 기다린다.
     * 호출 측이 @Transactional 범위 안에 있을 때 Kafka 발행 실패 시
     * 예외가 전파되어 DB 트랜잭션도 함께 롤백된다.
     * → DB 저장 성공 + Kafka 발행 실패로 인한 미처리 PENDING 주문 방지
     *
     * 파티션 키: userId → 같은 사용자의 주문은 항상 같은 파티션에 순서대로 처리됨
     */
    public void publishOrderSubmitted(TradeOrderMessage message) {
        try {
            kafkaTemplate.send(KafkaTopic.TRADE_ORDER_SUBMITTED, String.valueOf(message.userId()), message)
                    .get(); // 브로커 ACK 대기 — 실패 시 예외 발생 → @Transactional 롤백
        } catch (Exception e) {
            throw new RuntimeException("Kafka 주문 발행 실패 - orderId=" + message.orderId(), e);
        }
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
