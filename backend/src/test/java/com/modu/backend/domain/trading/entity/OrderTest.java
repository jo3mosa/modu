package com.modu.backend.domain.trading.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order 엔티티 상태 머신 / 불변식 검증 — S14P31B106-291 PR 리뷰 반영.
 *
 * 핵심 invariant:
 *  - markFilled 입력 (executedQuantity / executedPrice) > 0
 *  - markFilled 누적 가 총 quantity 초과 금지
 *  - isFinalFill=true 인데 실제 누적 != total 이면 거부
 *  - 부분 체결은 PENDING 유지, 전량 체결만 FILLED 전이
 *  - markReservationConverted newKisOrderNo null/blank 거부
 */
class OrderTest {

    // ──────────────────────────────────────────────────────────
    // markFilled — 입력 불변식
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("markFilled — executedQuantity <= 0 → IllegalArgumentException")
    void rejectNonPositiveQuantity() {
        Order order = pending(100L);

        assertThatThrownBy(() -> order.markFilled(0L, 70000L, false, OffsetDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executedQuantity");
        assertThatThrownBy(() -> order.markFilled(-1L, 70000L, false, OffsetDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("markFilled — executedPrice <= 0 → IllegalArgumentException")
    void rejectNonPositivePrice() {
        Order order = pending(100L);

        assertThatThrownBy(() -> order.markFilled(10L, 0L, false, OffsetDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executedPrice");
        assertThatThrownBy(() -> order.markFilled(10L, -1L, false, OffsetDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("markFilled — 잔여 수량 초과 시 거부")
    void rejectOverflowQuantity() {
        Order order = pending(100L);
        order.markFilled(30L, 70000L, false, OffsetDateTime.now()); // 누적 30

        // 잔여 70 인데 80 체결 시도
        assertThatThrownBy(() -> order.markFilled(80L, 70000L, false, OffsetDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remaining");
    }

    @Test
    @DisplayName("markFilled — isFinalFill=true 이지만 실제 누적 != total → 거부")
    void rejectInconsistentFinalFlag() {
        Order order = pending(100L);

        // 30주만 체결인데 isFinalFill=true 로 잘못 호출
        assertThatThrownBy(() -> order.markFilled(30L, 70000L, true, OffsetDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isFinalFill");
    }

    // ──────────────────────────────────────────────────────────
    // markFilled — 정상 흐름
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("부분 체결 → PENDING 유지 + 가중 평균 계산")
    void partialFillKeepsPending() {
        Order order = pending(100L);

        order.markFilled(30L, 70000L, false, OffsetDateTime.now());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getFilledQuantity()).isEqualTo(30L);
        assertThat(order.getFilledAvgPrice()).isEqualTo(70000L);
        assertThat(order.getFilledAt()).isNull();
    }

    @Test
    @DisplayName("부분 체결 누적 — 가중 평균 단가 정확")
    void weightedAverage() {
        Order order = pending(100L);
        order.markFilled(30L, 70000L, false, OffsetDateTime.now()); // 30 @ 70000
        order.markFilled(20L, 80000L, false, OffsetDateTime.now()); // 20 @ 80000

        assertThat(order.getFilledQuantity()).isEqualTo(50L);
        // (70000*30 + 80000*20) / 50 = (2,100,000 + 1,600,000) / 50 = 74,000
        assertThat(order.getFilledAvgPrice()).isEqualTo(74000L);
    }

    @Test
    @DisplayName("전량 체결 → FILLED + filledAt 기록")
    void finalFillTransitions() {
        Order order = pending(100L);
        OffsetDateTime executedAt = OffsetDateTime.now();

        order.markFilled(100L, 70000L, true, executedAt);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getFilledQuantity()).isEqualTo(100L);
        assertThat(order.getFilledAt()).isEqualTo(executedAt);
    }

    @Test
    @DisplayName("종착 상태 (FILLED) → 추가 markFilled 거부")
    void terminalGuardsFilled() {
        Order order = pending(10L);
        order.markFilled(10L, 70000L, true, OffsetDateTime.now()); // FILLED

        assertThatThrownBy(() -> order.markFilled(1L, 70000L, true, OffsetDateTime.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ──────────────────────────────────────────────────────────
    // markReservationConverted
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("markReservationConverted — newKisOrderNo null/blank → 거부")
    void reservationConvertedRejectsBlank() {
        Order order = reserved();

        assertThatThrownBy(() -> order.markReservationConverted(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.markReservationConverted(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.markReservationConverted("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("markReservationConverted — RESERVED → PENDING + kis_order_no 채움")
    void reservationConvertedHappyPath() {
        Order order = reserved();

        order.markReservationConverted("0000002891");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getKisOrderNo()).isEqualTo("0000002891");
    }

    @Test
    @DisplayName("markReservationConverted — RESERVED 아닌 상태에서 호출 → 거부")
    void reservationConvertedRejectsNonReserved() {
        Order order = pending(10L);

        assertThatThrownBy(() -> order.markReservationConverted("0000001"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ──────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────

    private static Order pending(long quantity) {
        return Order.builder()
                .userId(1L)
                .stockCode("005930")
                .side(OrderSide.BUY)
                .orderType(OrderType.LIMIT)
                .quantity(quantity)
                .limitPrice(70000L)
                .status(OrderStatus.PENDING)
                .source(OrderSource.AI_DECISION)
                .idempotencyKey("idem-" + quantity)
                .build();
    }

    private static Order reserved() {
        Order order = pending(10L);
        order.markReserved("RSVN_001", OffsetDateTime.now());
        return order;
    }
}
