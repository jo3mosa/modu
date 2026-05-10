package com.modu.backend.domain.trading.kafka.consumer;

import com.modu.backend.global.kafka.constant.KafkaConsumerGroup;
import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.TradeOrderExecutedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PortfolioUpdateConsumer {
    private final JdbcTemplate jdbcTemplate;

    @KafkaListener(
        topics = KafkaTopic.TRADE_ORDER_EXECUTED,
        groupId = KafkaConsumerGroup.PORTFOLIO_UPDATE,
        containerFactory = "portfolioUpdateFactory"
    )
    public void consume(TradeOrderExecutedMessage message, Acknowledgment ack) {
        try {
            log.info("체결 수신: orderId={}, stockCode={}, isFinalFill={}",
                message.orderId(), message.stockCode(), message.isFinalFill());

            // TODO: 1. order_executions INSERT
            // TODO: 2. orders UPDATE (filled_quantity, status 등)
            // TODO: 3. SELL && isFinalFill → trade_pnl_records INSERT
            // TODO: 4. isFinalFill → position_locks DELETE
            // TODO: 5. Redis 포지션 캐시 갱신
            // TODO: 6. WebSocket push

            ack.acknowledge();
        } catch (Exception e) {
            log.error("체결 처리 실패: orderId={}, error={}", message.orderId(), e.getMessage());
            // TODO: DLQ 발행
            ack.acknowledge();
        }
    }
}
