package com.modu.backend.domain.ai.dto;

import com.modu.backend.domain.ai.entity.AiExecutionStatus;
import com.modu.backend.domain.ai.entity.AiJudgment;

/**
 * 승인/거부 처리 후 응답 (S14P31B106-292)
 *
 * 승인: executionStatus=READY, orderId=<신규 Order id>
 * 거부: executionStatus=REJECTED, orderId=null
 */
public record DecisionApprovalResponse(
        Long judgmentId,
        AiExecutionStatus executionStatus,
        Long orderId
) {
    public static DecisionApprovalResponse from(AiJudgment j) {
        return new DecisionApprovalResponse(j.getId(), j.getExecutionStatus(), j.getOrderId());
    }
}
