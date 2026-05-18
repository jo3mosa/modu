package com.modu.backend.global.kafka.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * trade.settled 토픽 메시지 DTO — S14P31B106-291
 *
 * BE 가 매도 전량 체결 + trade_pnl_records INSERT 직후 발행. 회고(Post-Mortem) Agent 트리거.
 *
 * [필드 — 2026-05-14 AI 팀 합의 페이로드]
 *  eventId           = "trade_settled_<uuid>"  — 메시지 식별. AI 측 멱등키
 *  timestamp         발행 시각
 *  userId            거래 사용자
 *  aiJudgmentId      매수 시점 AI 판단 ID. 매수 주문이 AI source 면 채움. 수동 매수면 null
 *  tradePnlRecordId  방금 INSERT 한 trade_pnl_records.id
 *  rawReturn         (sellPrice − avgBuyPrice) / avgBuyPrice 소수 (예: 0.052 = 5.2%)
 *  alphaReturn       시장 대비 초과수익률. 본 PR 은 시장 지수 산출 미구현 → null (followups)
 *  holdingDays       매수 → 매도 사이 calendar day. 영업일 계산은 followups
 */
public record TradeSettledMessage(
        String         eventId,
        OffsetDateTime timestamp,
        Long           userId,
        Long           aiJudgmentId,
        Long           tradePnlRecordId,
        Double         rawReturn,
        Double         alphaReturn,
        Long           holdingDays
) {

    /**
     * 신규 메시지 생성 — eventId / timestamp 는 자동 채움.
     */
    public static TradeSettledMessage of(Long userId, Long aiJudgmentId, Long tradePnlRecordId,
                                         Double rawReturn, Double alphaReturn, Long holdingDays) {
        return new TradeSettledMessage(
                "trade_settled_" + UUID.randomUUID(),
                OffsetDateTime.now(),
                userId,
                aiJudgmentId,
                tradePnlRecordId,
                rawReturn,
                alphaReturn,
                holdingDays
        );
    }
}
