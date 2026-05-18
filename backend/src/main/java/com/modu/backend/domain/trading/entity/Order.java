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
        // null 체크 후 갱신 — 재정정/취소를 위해 기존 KIS 번호를 보존
        if (newKisOrderNo != null) this.kisOrderNo = newKisOrderNo;
        if (newKisOrgNo   != null) this.kisOrgNo   = newKisOrgNo;
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

    /**
     * KIS 거부 / 비동기 처리 실패로 인한 주문 거절 처리
     * KisOrderConsumer 가 KIS placeOrder 실패 시 호출 (S14P31B106-306)
     *
     * @param reason 거절 사유 (사용자에게 표시 가능한 메시지)
     */
    public void reject(String reason) {
        this.status       = OrderStatus.REJECTED;
        this.rejectReason = reason;
        this.updatedAt    = OffsetDateTime.now();
    }

    /**
     * KIS 예약주문 접수 성공 처리 (S14P31B106-336)
     *
     * placeReservedOrder 응답의 RSVN_ORD_SEQ 를 kis_rsvn_seq 에 저장.
     * KIS 일반 주문 (placeOrder) 가 발급하는 kis_order_no / kis_org_no 는 없음.
     *
     * @param kisRsvnSeq  KIS 예약주문 순번
     * @param submittedAt 접수 시각
     */
    public void markReserved(String kisRsvnSeq, OffsetDateTime submittedAt) {
        guardNotTerminal(OrderStatus.RESERVED);
        this.status      = OrderStatus.RESERVED;
        this.kisRsvnSeq  = kisRsvnSeq;
        this.submittedAt = submittedAt;
        this.updatedAt   = OffsetDateTime.now();
    }

    /**
     * 예약주문 발행 대기 상태로 전환 (S14P31B106-336)
     *
     * 자동매매 결정이 예약 가능 시간 직전 (E gap 15:30~15:40) 또는 공휴일 정규장 시간대에
     * 도달했을 때 사용. ReservedPendingOrderSweeper 가 예약 가능 시간 도래 시 picks up.
     */
    public void markReservedPending() {
        guardNotTerminal(OrderStatus.RESERVED_PENDING);
        this.status    = OrderStatus.RESERVED_PENDING;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 체결 단건 반영 (S14P31B106-291)
     *
     * 부분 체결이면 filled_quantity / filled_avg_price 누적만 갱신, 상태는 PENDING 유지.
     * 전량 체결 (isFinalFill=true) 이면 FILLED 로 전이 + filled_at 기록.
     *
     * [전이 가능 상태]
     *  PENDING / MODIFIED 만 허용. RESERVED 는 변환 동기화로 먼저 PENDING 으로 와야 함 — 변환 전
     *  H0STCNI0 도착 race 발생 시 IllegalStateException 으로 보호.
     *
     * [가중 평균 단가]
     *  (기존 누적금액 + 이번 체결금액) / 새 누적수량. 정수 나눗셈으로 1원 미만 절삭 — KIS 가 1원 단위라 무난.
     */
    public void markFilled(long executedQuantity, long executedPrice,
                           boolean isFinalFill, OffsetDateTime executedAt) {
        guardNotTerminal(OrderStatus.FILLED);
        if (this.status != OrderStatus.PENDING && this.status != OrderStatus.MODIFIED) {
            throw new IllegalStateException(
                    "체결 반영은 PENDING / MODIFIED 만 가능 - currentStatus: " + this.status);
        }
        // 입력 불변식 — 비정상 메시지 / 호출 실수 차단
        if (executedQuantity <= 0L) {
            throw new IllegalArgumentException("executedQuantity > 0 필요. actual: " + executedQuantity);
        }
        if (executedPrice <= 0L) {
            throw new IllegalArgumentException("executedPrice > 0 필요. actual: " + executedPrice);
        }
        long prevFilled = this.filledQuantity == null ? 0L : this.filledQuantity;
        long prevAvg    = this.filledAvgPrice  == null ? 0L : this.filledAvgPrice;
        long total      = this.quantity == null ? 0L : this.quantity;
        long remaining  = total - prevFilled;
        if (executedQuantity > remaining) {
            throw new IllegalArgumentException(
                    "executedQuantity 가 잔여 수량 초과 - executed: " + executedQuantity
                            + ", remaining: " + remaining);
        }
        long newFilled = prevFilled + executedQuantity;
        // 누적금액 / 가격 overflow 가드
        long prevAmount  = Math.multiplyExact(prevAvg, prevFilled);
        long thisAmount  = Math.multiplyExact(executedPrice, executedQuantity);
        long totalAmount = Math.addExact(prevAmount, thisAmount);
        long newAvg      = newFilled == 0L ? 0L : totalAmount / newFilled;

        this.filledQuantity = newFilled;
        this.filledAvgPrice = newAvg;
        // isFinalFill 은 실제 누적이 총량과 일치할 때만 신뢰
        boolean actualFinal = newFilled == total;
        if (isFinalFill && !actualFinal) {
            throw new IllegalArgumentException(
                    "isFinalFill=true 인데 누적 != 총량 - newFilled: " + newFilled + ", total: " + total);
        }
        if (actualFinal) {
            this.status   = OrderStatus.FILLED;
            this.filledAt = executedAt;
        }
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * 예약주문 → 일반 주문 전환 처리 (S14P31B106-291)
     *
     * 매일 09:05 KST 변환 모니터링 스케줄러가 KIS order-resv-ccnl 조회 후 발급된 새 ODER_NO 를 동기화.
     * RESERVED → PENDING 전이 + kis_order_no 채움. 이후 일반 체결 흐름 (H0STCNI0 → markFilled) 진입.
     *
     * @param newKisOrderNo KIS 변환 후 발급된 ODER_NO
     */
    public void markReservationConverted(String newKisOrderNo) {
        if (this.status != OrderStatus.RESERVED) {
            throw new IllegalStateException(
                    "RESERVED 만 변환 전이 가능 - currentStatus: " + this.status);
        }
        // null/blank 저장 시 이후 findByKisOrderNo 매칭 실패로 체결 이벤트 영구 누락
        if (newKisOrderNo == null || newKisOrderNo.isBlank()) {
            throw new IllegalArgumentException("newKisOrderNo 필수. null/blank 거부.");
        }
        this.kisOrderNo = newKisOrderNo;
        this.status     = OrderStatus.PENDING;
        this.updatedAt  = OffsetDateTime.now();
    }

    /**
     * 종착 상태 (FILLED / CANCELED / REJECTED) 에서 다른 상태로의 전이 차단 — 상태 머신 불변성 보호.
     * 호출자 (서비스 레이어) 가 락 + 사전 체크하지만 엔티티 레벨에서도 방어적 검증.
     */
    private void guardNotTerminal(OrderStatus target) {
        if (this.status == OrderStatus.FILLED
                || this.status == OrderStatus.CANCELED
                || this.status == OrderStatus.REJECTED) {
            throw new IllegalStateException(
                    "종착 상태에서 전이 불가 - currentStatus: " + this.status + ", attempted: " + target);
        }
    }
}
