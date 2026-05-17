package com.modu.backend.domain.ai.dto;

import com.modu.backend.domain.ai.entity.AgentMessage;
import com.modu.backend.domain.ai.entity.AgentType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "AI 에이전트 발화 메시지 단건")
public record AgentMessageResponse(
        @Schema(description = "메시지 ID", example = "12345")
        Long messageId,

        @Schema(description = "종목 코드", example = "005930")
        String stockCode,

        @Schema(description = "연결된 AI 판단 ID. 자유 발화는 null.", example = "501", nullable = true)
        Long judgmentId,

        @Schema(description = "에이전트 종류", example = "BULL", allowableValues = {"BULL", "BEAR", "STRATEGY", "DECIDE"})
        AgentType agent,

        @Schema(description = "같은 판단 내 발화 순서", example = "0")
        int seq,

        @Schema(description = "발화 본문", example = "RSI 58, MACD 골든크로스 임박, 5일선이 20일선 위에 잘 가고 있어요.")
        String text,

        @Schema(description = "발화 시각", example = "2026-05-18T10:23:45+09:00")
        OffsetDateTime createdAt
) {

    public static AgentMessageResponse from(AgentMessage message) {
        return new AgentMessageResponse(
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
