package com.modu.backend.domain.trading.position.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PositionThresholdTest {

    @Nested
    @DisplayName("updateAiThresholds — active_*_price 재계산")
    class UpdateAiThresholds {

        @Test
        @DisplayName("user/ai 둘 다 존재: 익절=min, 손절=max (둘 중 빨리 발동되는 쪽)")
        void bothPresentMinTargetMaxStopLoss() {
            // 매수가 10000원, 사용자 10% → user 익절 11000 / 손절 9000
            // AI 가 더 보수적: 익절 10500 / 손절 9500
            PositionThreshold p = build(11_000L, 9_000L);

            p.updateAiThresholds(10_500L, 9_500L);

            assertThat(p.getAiTargetPrice()).isEqualTo(10_500L);
            assertThat(p.getAiStopLossPrice()).isEqualTo(9_500L);
            assertThat(p.getActiveTargetPrice()).isEqualTo(10_500L);   // min(11000, 10500)
            assertThat(p.getActiveStopLossPrice()).isEqualTo(9_500L);  // max(9000, 9500)
        }

        @Test
        @DisplayName("AI 가 사용자보다 덜 보수적: active 는 사용자 임계가 채택")
        void userMoreConservative() {
            // user 익절 11000 / 손절 9000, AI 익절 12000 / 손절 8000
            PositionThreshold p = build(11_000L, 9_000L);

            p.updateAiThresholds(12_000L, 8_000L);

            assertThat(p.getActiveTargetPrice()).isEqualTo(11_000L);   // min(11000, 12000) = 사용자
            assertThat(p.getActiveStopLossPrice()).isEqualTo(9_000L);  // max(9000, 8000) = 사용자
        }

        @Test
        @DisplayName("AI 임계 둘 다 null: active 가 사용자 임계 그대로")
        void aiNullKeepsUser() {
            PositionThreshold p = build(11_000L, 9_000L);

            p.updateAiThresholds(null, null);

            assertThat(p.getAiTargetPrice()).isNull();
            assertThat(p.getAiStopLossPrice()).isNull();
            assertThat(p.getActiveTargetPrice()).isEqualTo(11_000L);
            assertThat(p.getActiveStopLossPrice()).isEqualTo(9_000L);
        }

        @Test
        @DisplayName("사용자 임계 null + AI 임계만 존재: active 가 AI 임계 채택")
        void userNullAdoptsAi() {
            PositionThreshold p = build(null, null);

            p.updateAiThresholds(10_500L, 9_500L);

            assertThat(p.getActiveTargetPrice()).isEqualTo(10_500L);
            assertThat(p.getActiveStopLossPrice()).isEqualTo(9_500L);
        }

        @Test
        @DisplayName("user/ai 둘 다 동일: active 도 동일")
        void equalValues() {
            PositionThreshold p = build(10_000L, 9_000L);

            p.updateAiThresholds(10_000L, 9_000L);

            assertThat(p.getActiveTargetPrice()).isEqualTo(10_000L);
            assertThat(p.getActiveStopLossPrice()).isEqualTo(9_000L);
        }

        @Test
        @DisplayName("AI 익절만 존재 / 손절 null")
        void aiTargetOnly() {
            PositionThreshold p = build(11_000L, 9_000L);

            p.updateAiThresholds(10_500L, null);

            assertThat(p.getActiveTargetPrice()).isEqualTo(10_500L);   // min(11000, 10500)
            assertThat(p.getActiveStopLossPrice()).isEqualTo(9_000L);  // ai null → user
        }
    }

    /**
     * user_take_profit_price, user_stop_loss_price 만 세팅된 활성 row 생성
     * builder 단계에서 active_*_price 도 user 값으로 초기화 (체결 핸들러 책임 시뮬레이션)
     */
    private PositionThreshold build(Long userTakeProfit, Long userStopLoss) {
        return PositionThreshold.builder()
                .userId(1L)
                .stockCode("005930")
                .sourceOrderId(100L)
                .quantity(10L)
                .avgEntryPrice(10_000L)
                .userTakeProfitPrice(userTakeProfit)
                .userStopLossPrice(userStopLoss)
                .activeTargetPrice(userTakeProfit)
                .activeStopLossPrice(userStopLoss)
                .build();
    }
}
