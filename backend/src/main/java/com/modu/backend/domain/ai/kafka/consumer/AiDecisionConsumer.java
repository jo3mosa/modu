package com.modu.backend.domain.ai.kafka.consumer;

import com.modu.backend.domain.ai.service.SignalHandlerService;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.kafka.constant.KafkaConsumerGroup;
import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.AiDecisionMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * ai.decision.generated 토픽 소비자 (S14P31B106-263)
 *
 * [책임 분리]
 *  - Consumer: 메시지 수신 + 실패 정책에 따른 ack/미커밋 분기만
 *  - SignalHandlerService: 검증, 매핑, ai_judgments INSERT, 후속 Kafka 발행, 멱등 처리
 *
 * [실패 정책]
 *  - 비즈니스 예외 (ApiException): ack — 재시도해도 같은 결과. 메시지 격리하고 다음으로 진행
 *  - 시스템 예외 (그 외 RuntimeException): 미커밋 + 재시도. ackMode=MANUAL_IMMEDIATE 이므로
 *    ack 호출 없이 빠져나가면 자동으로 재처리됨
 *  - DLQ 적재 정책은 별도 이슈
 *
 * [멱등성]
 *  ai_judgments (user_id, source_event_id) partial unique index 가 1차 방어선.
 *  SignalHandlerService 가 중복 INSERT 시 silent skip + 정상 종료 → ack.
 *
 * [동시성]
 *  KafkaConsumerConfig.aiDecisionListenerContainerFactory — concurrency=3
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiDecisionConsumer {

    private final SignalHandlerService signalHandlerService;

    @KafkaListener(
            topics = KafkaTopic.AI_DECISION_GENERATED,
            groupId = KafkaConsumerGroup.AI_DECISION,
            containerFactory = "aiDecisionListenerContainerFactory"
    )
    public void onMessage(AiDecisionMessage message, Acknowledgment ack) {
        try {
            signalHandlerService.handle(message);
            ack.acknowledge();

        } catch (ApiException e) {
            // 비즈니스 예외 — 재시도해도 동일 결과. 격리 ack
            log.warn("AI 판단 메시지 비즈니스 처리 실패 - sourceEventId: {}, userId: {}, errorCode: {}",
                    safeSourceEventId(message), safeUserId(message), e.getErrorCode(), e);
            ack.acknowledge();

        } catch (Exception e) {
            // 시스템 예외 — 미커밋, 자동 재시도
            log.error("AI 판단 메시지 시스템 예외 (재시도 예정) - sourceEventId: {}, userId: {}",
                    safeSourceEventId(message), safeUserId(message), e);
            throw e;
        }
    }

    private String safeSourceEventId(AiDecisionMessage message) {
        return message == null ? "null" : String.valueOf(message.sourceEventId());
    }

    private String safeUserId(AiDecisionMessage message) {
        return message == null ? "null" : String.valueOf(message.userId());
    }
}
