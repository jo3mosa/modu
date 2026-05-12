package com.modu.backend.domain.trading.kafka.consumer;

import com.modu.backend.domain.trading.client.KisOrderClient;
import com.modu.backend.domain.trading.dto.OrderSseEvent;
import com.modu.backend.domain.trading.entity.*;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.trading.service.OrderService;
import com.modu.backend.domain.trading.sse.OrderSseEmitterManager;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.kafka.constant.KafkaConsumerGroup;
import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisOrderConsumer {

    private final OrderRepository orderRepository;
    private final KisCredentialRepository kisCredentialRepository;
    private final KisTokenService kisTokenService;
    private final KisOrderClient kisOrderClient;
    private final AesGcmEncryptor encryptor;
    private final OrderSseEmitterManager orderSseEmitterManager;
    // KIS API 호출과 DB 업데이트를 분리하기 위해 OrderService를 통해 트랜잭션 처리
    private final OrderService orderService;

    @KafkaListener(
        topics = KafkaTopic.TRADE_ORDER_SUBMITTED,
        groupId = KafkaConsumerGroup.KIS_ORDER,
        containerFactory = "kisOrderFactory"
    )
    public void consume(TradeOrderMessage message, Acknowledgment ack) {
        // catch 블록에서도 orderId를 참조할 수 있도록 try 밖에 선언
        // 주문 조회 성공 시 DB PK, 실패 시 null (→ catch에서 idempotencyKey로 폴백)
        Order order = null;
        try {
            log.info("주문 수신: orderId={}, userId={}, stockCode={}",
                message.orderId(), message.userId(), message.stockCode());

            // 1. 기존 PENDING 주문 조회 (Kafka 발행 전 OrderService/SignalHandler에서 저장)
            order = orderRepository
                    .findByUserIdAndIdempotencyKey(message.userId(), message.orderId())
                    .orElseThrow(() -> new RuntimeException("주문 없음 - orderId=" + message.orderId()));

            // 2. 이미 KIS에 접수된 주문이면 skip (Consumer 재시도 중복 방지)
            if (order.getKisOrderNo() != null) {
                log.info("이미 처리된 주문 무시 - orderId={}", message.orderId());
                ack.acknowledge();
                return;
            }

            // 3. KIS API 주문 호출
            KisCredential credential = kisCredentialRepository.findByUserId(message.userId())
                    .orElseThrow(() -> new RuntimeException("KIS 미연동 userId=" + message.userId()));

            String appKey      = encryptor.decrypt(credential.getAppKeyEnc());
            String appSecret   = encryptor.decrypt(credential.getAppSecretEnc());
            String accessToken = kisTokenService.getOrIssueAccessToken(message.userId(), appKey, appSecret);

            KisOrderClient.KisOrderResult result = kisOrderClient.placeOrder(
                    accessToken, appKey, appSecret,
                    credential.getAccountNo(), credential.getAccountPrdtCd(),
                    order.getStockCode(),
                    order.getSide(),
                    order.getOrderType(),
                    order.getQuantity().intValue(),
                    order.getLimitPrice() != null ? order.getLimitPrice() : 0L
            );

            // 4. orders.kis_order_no UPDATE — KIS API 호출과 트랜잭션을 분리하여 DB 커넥션 점유 최소화
            orderService.updateKisOrderInfo(order.getId(), result.kisOrderNo(), result.kisOrgNo());

            log.info("주문 접수 완료 - userId={}, stockCode={}, source={}, kisOrderNo={}",
                    message.userId(), message.stockCode(), message.source(), result.kisOrderNo());

            // 5. SSE push → 사용자에게 주문 접수 결과 전달
            orderSseEmitterManager.send(message.userId(), new OrderSseEvent(
                    "ORDER_SUBMITTED",
                    String.valueOf(order.getId()),
                    order.getStockCode(),
                    result.kisOrderNo(),
                    "SUBMITTED",
                    "주문이 접수되었습니다"
            ));

            ack.acknowledge();
        } catch (Exception e) {
            log.error("주문 처리 실패: orderId={}, error={}", message.orderId(), e.getMessage(), e);

            // 실패 시 SSE push — 성공과 동일하게 DB PK 사용, 주문 조회 전 실패 시 idempotencyKey로 폴백
            String failedOrderId = order != null ? String.valueOf(order.getId()) : message.orderId();
            orderSseEmitterManager.send(message.userId(), new OrderSseEvent(
                    "ORDER_FAILED",
                    failedOrderId,
                    message.stockCode(),
                    null,
                    "FAILED",
                    "주문 처리에 실패했습니다"
            ));

            // TODO: DLQ 발행
            ack.acknowledge(); // 실패해도 커밋 (무한 재시도 방지)
        }
    }
}
