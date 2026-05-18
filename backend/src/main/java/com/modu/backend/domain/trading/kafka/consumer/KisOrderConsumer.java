package com.modu.backend.domain.trading.kafka.consumer;

import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.trading.service.OrderDispatchService;
import com.modu.backend.global.kafka.constant.KafkaConsumerGroup;
import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * trade.order.submitted 토픽 소비자
 *
 * 수동/AI/손절익절 모든 주문 메시지를 받아 OrderDispatchService 에 위임.
 * 분기 (정규장 / 예약주문 / 대기 / 거절) / 모의계좌 차단 / Kill Switch 카운트 정책은 모두 DispatchService.
 *
 * [본 Consumer 의 책임]
 *  1. message.orderId (= idempotencyKey) 로 Order row 찾기
 *  2. row 없으면 메시지 무시 (잘못 발행된 메시지)
 *  3. Order.id 를 dispatchService 에 전달
 *  4. 어떤 결과든 ack — 무한 재처리 차단
 *
 * [ack 정책]
 *  성공/실패/skip/예외 모두 ack — DLQ 적재는 후순위 별도 이슈.
 *
 * [동시성]
 *  KafkaConsumerConfig.kisOrderListenerContainerFactory — concurrency=1 (KIS rate limit 직렬화)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisOrderConsumer {

    private final OrderRepository orderRepository;
    private final OrderDispatchService orderDispatchService;

    @KafkaListener(
            topics = KafkaTopic.TRADE_ORDER_SUBMITTED,
            groupId = KafkaConsumerGroup.KIS_ORDER,
            containerFactory = "kisOrderListenerContainerFactory"
    )
    public void onMessage(TradeOrderMessage message, Acknowledgment ack) {
        try {
            Optional<Order> opt = orderRepository
                    .findByUserIdAndIdempotencyKey(message.userId(), message.orderId());
            if (opt.isEmpty()) {
                log.error("주문 row 없음 — 메시지 무시. orderId: {}, userId: {}",
                        message.orderId(), message.userId());
                return;
            }
            orderDispatchService.dispatch(opt.get().getId());
        } catch (Exception e) {
            log.error("KisOrderConsumer 미처리 예외 - orderId: {}", message.orderId(), e);
        } finally {
            ack.acknowledge();
        }
    }
}
