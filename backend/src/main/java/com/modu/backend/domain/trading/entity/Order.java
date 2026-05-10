package com.modu.backend.domain.trading.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 주문 엔티티 (orders 테이블)
 *
 * [side]      BUY / SELL
 * [orderType] LIMIT(지정가) / MARKET(시장가)
 * [status]    PENDING → FILLED / CANCELED / REJECTED
 * [source]    MANUAL(사용자 직접) / AUTO(AI 자동매매)
 *
 * commission, tax는 체결 전 0으로 초기화 — 체결 통보 후 갱신
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stock_code", nullable = false)
    private String stockCode;

    @Column(name = "parent_order_id")
    private Long parentOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 20)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 20)
    private OrderType orderType;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "limit_price")
    private Long limitPrice;

    @Column(name = "filled_quantity", nullable = false)
    private Long filledQuantity;

    @Column(name = "filled_avg_price")
    private Long filledAvgPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private OrderSource source;

    @Column(name = "kis_order_no")
    private String kisOrderNo;

    @Column(name = "kis_rsvn_seq")
    private String kisRsvnSeq;

    @Column(name = "kis_org_no")
    private String kisOrgNo;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "rule_history_id")
    private Long ruleHistoryId;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "filled_at")
    private OffsetDateTime filledAt;

    @Column(name = "commission", nullable = false)
    private Long commission;

    @Column(name = "tax", nullable = false)
    private Long tax;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    public Order(Long userId, String stockCode, OrderSide side, OrderType orderType,
                 Long quantity, Long limitPrice, OrderStatus status, OrderSource source,
                 String idempotencyKey) {
        this.userId = userId;
        this.stockCode = stockCode;
        this.side = side;
        this.orderType = orderType;
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.filledQuantity = 0L;
        this.status = status;
        this.source = source;
        this.idempotencyKey = idempotencyKey;
        this.commission = 0L;
        this.tax = 0L;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * KIS 주문 접수 성공 후 응답값 반영
     * DB 저장(PENDING) 이후 KIS 호출 결과를 업데이트할 때 사용
     */
    public void updateKisInfo(String kisOrderNo, String kisOrgNo, OffsetDateTime submittedAt) {
        this.kisOrderNo = kisOrderNo;
        this.kisOrgNo = kisOrgNo;
        this.submittedAt = submittedAt;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 주문 정정 처리
     * KIS 정정 후 새로 발급된 주문번호/KRX조직번호로 업데이트.
     * 재정정·취소를 위해 kis_order_no, kis_org_no 를 반드시 갱신한다.
     *
     * @param newLimitPrice  변경할 가격 (null 이면 기존 가격 유지)
     * @param newQuantity    변경할 수량 (null 이면 기존 수량 유지)
     * @param newKisOrderNo  KIS 발급 새 주문번호 (ODNO)
     * @param newKisOrgNo    KIS 발급 새 KRX 조직번호 (KRX_FWDG_ORD_ORGNO)
     */
    public void modify(Long newLimitPrice, Long newQuantity,
                       String newKisOrderNo, String newKisOrgNo) {
        if (newLimitPrice != null) this.limitPrice = newLimitPrice;
        if (newQuantity   != null) this.quantity   = newQuantity;
        this.kisOrderNo = newKisOrderNo;
        this.kisOrgNo   = newKisOrgNo;
        this.status     = OrderStatus.MODIFIED;
        this.updatedAt  = OffsetDateTime.now();
    }

    /**
     * 주문 취소 처리
     */
    public void cancel(OffsetDateTime cancelledAt) {
        this.status      = OrderStatus.CANCELED;
        this.cancelledAt = cancelledAt;
        this.updatedAt   = OffsetDateTime.now();
    }
}
