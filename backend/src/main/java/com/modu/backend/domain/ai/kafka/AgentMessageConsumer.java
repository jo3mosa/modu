package com.modu.backend.domain.ai.kafka;

import com.modu.backend.domain.ai.entity.AgentType;
import com.modu.backend.domain.ai.service.AgentMessageService;
import com.modu.backend.global.error.ValidationException;
import com.modu.backend.global.kafka.constant.KafkaConsumerGroup;
import com.modu.backend.global.kafka.constant.KafkaTopic;
import com.modu.backend.global.kafka.dto.AgentMessageKafkaMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * ai.agent.message 토픽 소비자
 *
 * AI 파트(ai_agent Python) 가 각 에이전트(BULL/BEAR/STRATEGY/DECIDE) 호출 완료 시점에
 * 1건씩 발행한 메시지를 받아 영속화 + SSE 푸시.
 *
 * [처리 흐름]
 *  1. payload 검증 — agent 문자열 → AgentType enum 변환, 필수 필드 체크
 *  2. AgentMessageService.save(SaveCommand) — INSERT + AFTER_COMMIT SSE 푸시 이벤트
 *  3. ack
 *
 * [ack 정책] KisOrderConsumer 와 동일 — 성공/실패/예외 모두 ack.
 * 메시지 포맷 오류로 무한 재처리 방지. DLQ 적재는 후순위 별도 이슈.
 *
 * [멱등성]
 * AI 재시도로 동일 (user_id, judgment_id, agent, seq) 가 재발행되어도 Service 가 skip.
 * 추가로 DB partial unique index 가 동시성 race 까지 방어.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMessageConsumer {

    private final AgentMessageService agentMessageService;

    @KafkaListener(
            topics = KafkaTopic.AI_AGENT_MESSAGE,
            groupId = KafkaConsumerGroup.AGENT_MESSAGE,
            containerFactory = "agentMessageListenerContainerFactory"
    )
    public void onMessage(AgentMessageKafkaMessage message, Acknowledgment ack) {
        try {
            processMessage(message);
        } catch (Exception e) {
            // 메시지 자체 문제 — 재시도해도 동일하게 실패하므로 흡수 + ack
            log.error("AgentMessageConsumer 처리 실패 — 메시지 skip. payload: {}", message, e);
        } finally {
            ack.acknowledge();
        }
    }

    private void processMessage(AgentMessageKafkaMessage message) {
        if (message == null) {
            log.warn("AgentMessage payload null — skip");
            return;
        }

        AgentType agentType = parseAgent(message.agent());
        int seq = message.seq() != null ? message.seq() : 0;

        AgentMessageService.SaveCommand cmd = new AgentMessageService.SaveCommand(
                message.userId(),
                message.stockCode(),
                message.judgmentId(),
                agentType,
                seq,
                message.text(),
                message.createdAt()
        );

        try {
            agentMessageService.save(cmd);
        } catch (ValidationException ve) {
            log.warn("AgentMessage 검증 실패 — skip. reason: {}, payload: {}", ve.getMessage(), message);
        }
    }

    /**
     * 외부 계약(String) → enum 변환. 알 수 없는 값은 명시적 예외.
     */
    private AgentType parseAgent(String agent) {
        if (agent == null || agent.isBlank()) {
            throw new ValidationException("agent 필드가 비어있습니다.");
        }
        try {
            return AgentType.valueOf(agent.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("알 수 없는 agent 값: " + agent);
        }
    }
}
