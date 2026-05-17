package com.modu.backend.domain.ai.sse;

import com.modu.backend.domain.ai.entity.AgentMessage;
import com.modu.backend.domain.ai.entity.AgentType;

import java.time.OffsetDateTime;

/**
 * SSE 로 프론트에 전송하는 에이전트 메시지 페이로드
 *
 * 프론트는 EventSource.addEventListener("agent-message", ...) 로 구독.
 * 동일 SSE 연결(/api/v1/orders/connect)에 기존 "order" 이벤트와 함께 흐른다.
 *
 * [DB 와의 차이]
 * DB row 의 user_id 는 노출하지 않음 (자기 자신 메시지만 받기 때문에 불필요).
 */
public record AgentMessageSseEvent(
        Long messageId,
        String stockCode,
        Long judgmentId,
        AgentType agent,
        int seq,
        String text,
        OffsetDateTime createdAt
) {

    /** SSE event name (프론트 addEventListener key) */
    public static final String EVENT_NAME = "agent-message";

    public static AgentMessageSseEvent from(AgentMessage message) {
        return new AgentMessageSseEvent(
                message.getId(),
                message.getStockCode(),
                message.getJudgmentId(),
                message.getAgent(),
                message.getSeq(),
                message.getText(),
                message.getCreatedAt()
        );
    }
}
