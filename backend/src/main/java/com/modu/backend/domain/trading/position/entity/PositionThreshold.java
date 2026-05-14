package com.modu.backend.domain.trading.position.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 포지션 임계가 엔티티 (position_thresholds 테이블)
 *
 * Position Monitor (S14P31B106-302) 가 첫 사용자. 후속:
 *  - 체결 핸들러(291) : quantity, avg_entry_price, user_*_price 갱신 + 신규 row INSERT
 *  - AI 핸들러(263)   : ai_*_price 갱신 + active_*_price 재계산
 *
 * [컬럼 매핑]
 *  active_target_price    : 익절 활성가 = min(user_take_profit_price, ai_target_price)
 *  active_stop_loss_price : 손절 활성가 = min(user_stop_loss_price, ai_stop_loss_price)
 *
 * [Partial Unique Index]
 *  (user_id, stock_code) WHERE is_active=TRUE — 활성 row 는 유저×종목당 1개
 *  Position Monitor 가 트리거 시 is_active=FALSE 로 전환하여 신규 매수 시 INSERT 가능하게 함
 */
@Entity
@Table(name = "position_thresholds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PositionThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stock_code", nullable = false)
    private String stockCode;

    @Column(name = "ai_judgment_id")
    private Long aiJudgmentId;

    @Column(name = "source_order_id", nullable = false)
    private Long sourceOrderId;

    @Column(name = "last_order_id")
    private Long lastOrderId;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "avg_entry_price", nullable = false)
    private Long avgEntryPrice;

    @Column(name = "ai_target_price")
    private Long aiTargetPrice;

    @Column(name = "ai_stop_loss_price")
    private Long aiStopLossPrice;

    @Column(name = "user_take_profit_price")
    private Long userTakeProfitPrice;

    @Column(name = "user_stop_loss_price")
    private Long userStopLossPrice;

    @Column(name = "active_target_price")
    private Long activeTargetPrice;

    @Column(name = "active_stop_loss_price")
    private Long activeStopLossPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_reason", length = 30)
    private PositionTriggerReason triggeredReason;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Builder
    public PositionThreshold(
            Long userId,
            String stockCode,
            Long aiJudgmentId,
            Long sourceOrderId,
            Long lastOrderId,
            Long quantity,
            Long avgEntryPrice,
            Long aiTargetPrice,
            Long aiStopLossPrice,
            Long userTakeProfitPrice,
            Long userStopLossPrice,
            Long activeTargetPrice,
            Long activeStopLossPrice
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        this.userId = userId;
        this.stockCode = stockCode;
        this.aiJudgmentId = aiJudgmentId;
        this.sourceOrderId = sourceOrderId;
        this.lastOrderId = lastOrderId;
        this.quantity = quantity;
        this.avgEntryPrice = avgEntryPrice;
        this.aiTargetPrice = aiTargetPrice;
        this.aiStopLossPrice = aiStopLossPrice;
        this.userTakeProfitPrice = userTakeProfitPrice;
        this.userStopLossPrice = userStopLossPrice;
        this.activeTargetPrice = activeTargetPrice;
        this.activeStopLossPrice = activeStopLossPrice;
        this.isActive = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 트리거 발동 처리 — is_active=FALSE 전환 + 사유/종료시각 기록
     * Position Monitor 가 Kafka 발행 성공 후 호출
     */
    public void markTriggered(PositionTriggerReason reason, Long triggeredOrderId) {
        this.isActive = false;
        this.triggeredReason = reason;
        this.lastOrderId = triggeredOrderId;
        OffsetDateTime now = OffsetDateTime.now();
        this.closedAt = now;
        this.updatedAt = now;
    }
}
