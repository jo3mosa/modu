package com.modu.backend.domain.trading.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 체결 단건 (order_executions 테이블) — S14P31B106-291
 *
 * 한 주문(orders.id) 이 부분 체결되면 본 row 가 N 건 INSERT 됨.
 * 전량 체결 시점 = orders.filled_quantity 누적이 orders.quantity 도달 = isFinalFill.
 *
 * [Idempotency]
 *  (order_id, kis_execution_no) UNIQUE 인덱스 (V20260518185000) — KIS 가 중복 통보해도 ON CONFLICT 로 무시.
 *
 * [필드 매핑 — KIS H0STCNI0]
 *  kis_execution_no  ← KIS 응답 ODER_NO (KIS 측 체결번호 — KIS 도 동일 ODER_NO 로 단건 식별)
 *  executed_quantity ← CNTG_QTY
 *  executed_price    ← CNTG_UNPR
 *  executed_amount   = executed_quantity × executed_price (BE 계산)
 *  executed_at       ← STCK_CNTG_HOUR (HHmmss) — 당일 KST 로 보강
 *  received_at       = BE 가 메시지 받은 시각
 */
@Entity
@Table(name = "order_executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "kis_execution_no", nullable = false)
    private String kisExecutionNo;

    @Column(name = "executed_quantity", nullable = false)
    private Long executedQuantity;

    @Column(name = "executed_price", nullable = false)
    private Long executedPrice;

    @Column(name = "executed_amount", nullable = false)
    private Long executedAmount;

    @Column(name = "executed_at", nullable = false)
    private OffsetDateTime executedAt;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Builder
    public OrderExecution(Long userId, Long orderId, String kisExecutionNo,
                          Long executedQuantity, Long executedPrice,
                          OffsetDateTime executedAt, OffsetDateTime receivedAt) {
        if (userId == null || orderId == null || kisExecutionNo == null
                || executedQuantity == null || executedPrice == null
                || executedAt == null || receivedAt == null) {
            throw new IllegalArgumentException("OrderExecution 필수 인자 누락");
        }
        if (executedQuantity <= 0L || executedPrice <= 0L) {
            throw new IllegalArgumentException(
                    "executedQuantity / executedPrice > 0 필요 - qty: " + executedQuantity
                            + ", price: " + executedPrice);
        }
        this.userId = userId;
        this.orderId = orderId;
        this.kisExecutionNo = kisExecutionNo;
        this.executedQuantity = executedQuantity;
        this.executedPrice = executedPrice;
        try {
            // 정산 / PnL 핵심 필드 — silent wraparound 차단
            this.executedAmount = Math.multiplyExact(executedQuantity, executedPrice);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "executedAmount 오버플로우 - qty: " + executedQuantity + ", price: " + executedPrice, e);
        }
        this.executedAt = executedAt;
        this.receivedAt = receivedAt;
    }
}
