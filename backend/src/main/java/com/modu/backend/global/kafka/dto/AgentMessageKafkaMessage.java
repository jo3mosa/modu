package com.modu.backend.global.kafka.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;

/**
 * ai.agent.message 토픽 메시지 DTO
 *
 * 발행자: ai_agent (Python) — 각 에이전트(BULL/BEAR/STRATEGY/DECIDE) 호출 완료 시점에 1건씩 publish
 * 소비자: AgentMessageConsumer
 *
 * [네이밍]
 * AI 페이로드는 snake_case, 자바 필드는 camelCase. @JsonNaming(SnakeCaseStrategy) 로 매핑.
 *
 * [필수/선택]
 *  - 필수: user_id, stock_code, agent, seq, text
 *  - 선택: judgment_id (단독 발화 시 null 허용), created_at (없으면 BE 가 NOW())
 *
 * [멱등키]
 * (user_id, judgment_id, agent, seq) 조합. AI 가 같은 발화를 재시도해도 partial unique index
 * 가 중복 INSERT 차단. judgment_id 가 null 인 자유 발화는 멱등 보장 대상에서 제외.
 *
 * [agent 값]
 * 외부 계약이므로 String 으로 받는다. 잘못된 값은 Service 검증에서 거른다 (AiDecisionMessage 패턴 동일).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentMessageKafkaMessage(
        Long userId,
        String stockCode,
        Long judgmentId,
        String agent,
        Integer seq,
        String text,
        OffsetDateTime createdAt
) {}
