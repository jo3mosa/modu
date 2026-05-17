package com.modu.backend.domain.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * AI 에이전트 발화 1건 (agent_messages 테이블)
 *
 * 매핑 출처: V20260518100000__add_agent_messages.sql
 *
 * 한 AI 판단(ai_judgments) 에 종속될 수도, 독립 발화일 수도 있다.
 * judgment_id 가 NULL 이면 자유 발화(향후 사용자→에이전트 대화 등 확장 여지).
 */
@Entity
@Table(name = "agent_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    /** ai_judgments.id FK. ON DELETE SET NULL — 판단 삭제되어도 대화는 보존 */
    @Column(name = "judgment_id")
    private Long judgmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent", nullable = false, length = 20)
    private AgentType agent;

    /** 같은 판단 내 발화 순서 (BULL → BEAR → STRATEGY → DECIDE = 0..3 권장) */
    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Builder
    private AgentMessage(
            Long userId,
            String stockCode,
            Long judgmentId,
            AgentType agent,
            int seq,
            String text,
            OffsetDateTime createdAt
    ) {
        this.userId = userId;
        this.stockCode = stockCode;
        this.judgmentId = judgmentId;
        this.agent = agent;
        this.seq = seq;
        this.text = text;
        this.createdAt = createdAt != null ? createdAt : OffsetDateTime.now();
    }
}
