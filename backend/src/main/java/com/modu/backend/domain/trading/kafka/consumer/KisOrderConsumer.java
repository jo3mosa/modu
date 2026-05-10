package com.modu.backend.domain.trading.kafka.consumer;

import com.modu.backend.global.kafka.constant.KafkaConsumerGroup;
import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisOrderConsumer {

    @KafkaListener(
        topics = KafkaTopic.TRADE_ORDER_SUBMITTED,
        groupId = KafkaConsumerGroup.KIS_ORDER,
        containerFactory = "kisOrderFactory"
    )
    public void consume(TradeOrderMessage message, Acknowledgment ack) {
        try {
            log.info("주문 수신: orderId={}, userId={}, stockCode={}",
                message.orderId(), message.userId(), message.stockCode());

            // TODO: 1. idempotency 체크 (orders WHERE idempotency_key = orderId)
            // TODO: 2. orders INSERT (status=SUBMITTED)
            // TODO: 3. KIS API 주문 호출
            // TODO: 4. orders.kis_order_no UPDATE

            ack.acknowledge();  // offset 커밋 → "이 메시지 처리했다"
        } catch (Exception e) {
            log.error("주문 처리 실패: orderId={}, error={}", message.orderId(), e.getMessage());
            // TODO: DLQ 발행
            ack.acknowledge(); // 실패해도 커밋 (무한 재시도 방지)
        }
    }
}
