package com.modu.backend.domain.ai.kafka.consumer;

import com.modu.backend.domain.ai.service.SignalHandlerService;
import com.modu.backend.global.kafka.constant.KafkaConsumerGroup;
import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.AiDecisionMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * AI 판단 결과 Consumer
 *
 * [역할]
 * ai.decision.generated 토픽을 구독하여 AI 에이전트가 발행한 판단 메시지를 수신한다.
 * 비즈니스 로직은 SignalHandlerService에 위임하고, 이 클래스는 Kafka 인프라만 담당한다.
 *
 * [파티션 키]
 * AI 에이전트가 user_id를 키로 발행하므로, 같은 사용자의 판단은 항상 같은 파티션에 순서대로 처리된다.
 *
 * [실패 처리]
 * 처리 실패 시 offset을 커밋하지 않고 로그만 남긴다.
 * TODO: DLQ(Dead Letter Queue) 발행으로 교체 필요
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiDecisionConsumer {

    private final SignalHandlerService signalHandlerService;

    @KafkaListener(
        topics = KafkaTopic.AI_DECISION_GENERATED,
        groupId = KafkaConsumerGroup.AI_DECISION,
        containerFactory = "aiDecisionFactory"
    )
    public void consume(AiDecisionMessage message, Acknowledgment ack) {
        try {
            log.info("AI 판단 수신 - userId={}, stockCode={}, decision={}",
                    message.userId(), message.stockCode(), message.resolveDecision());

            // 비즈니스 로직 위임 (DB 저장 + 필요 시 주문 발행)
            signalHandlerService.handle(message);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("AI 판단 처리 실패 - userId={}, stockCode={}, error={}",
                    message.userId(), message.stockCode(), e.getMessage(), e);

            // TODO: DLQ 발행
            ack.acknowledge(); // 실패해도 커밋 (무한 재시도 방지)
        }
    }
}
