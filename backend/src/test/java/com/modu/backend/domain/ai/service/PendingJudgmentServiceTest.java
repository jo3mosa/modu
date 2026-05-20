package com.modu.backend.domain.ai.service;

import com.modu.backend.domain.ai.dto.DecisionApprovalResponse;
import com.modu.backend.domain.ai.dto.PendingDecisionResponse;
import com.modu.backend.domain.ai.entity.AiExecutionStatus;
import com.modu.backend.domain.ai.entity.AiJudgment;
import com.modu.backend.domain.ai.exception.AiErrorCode;
import com.modu.backend.domain.ai.repository.AiJudgmentRepository;
import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.domain.trading.kafka.producer.TradeOrderProducer;
import com.modu.backend.domain.trading.position.repository.PositionThresholdRepository;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.global.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PendingJudgmentServiceTest {

    @Mock AiJudgmentRepository aiJudgmentRepository;
    @Mock OrderRepository orderRepository;
    @Mock PositionThresholdRepository positionThresholdRepository;
    @Mock TradeOrderProducer tradeOrderProducer;

    @InjectMocks
    PendingJudgmentService service;

    private static final Long USER_ID = 1L;
    private static final String STOCK = "005930";

    @Test
    @DisplayName("listPending: APPROVAL_REQUIRED + 미만료 row 만 반환")
    void listPendingFiltersExpired() {
        AiJudgment valid = buildPending(11L, OffsetDateTime.now().plusMinutes(3));
        AiJudgment expired = buildPending(12L, OffsetDateTime.now().minusMinutes(1));
        when(aiJudgmentRepository
                .findByUserIdAndExecutionStatusInOrderByJudgedAtDesc(
                        org.mockito.ArgumentMatchers.eq(USER_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(valid, expired));

        List<PendingDecisionResponse> result = service.listPending(USER_ID);

        assertThat(result).extracting(PendingDecisionResponse::id).containsExactly(11L);
    }

    @Test
    @DisplayName("approve: Order INSERT + Kafka 발행(afterCommit 폴백) + judgment.markApproved")
    void approveCreatesOrderAndMarks() {
        AiJudgment judgment = buildPending(20L, OffsetDateTime.now().plusMinutes(3));
        when(aiJudgmentRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(judgment));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> {
            Order saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 555L);
            return saved;
        });
        when(positionThresholdRepository.findByUserIdAndStockCodeAndIsActiveTrue(USER_ID, STOCK))
                .thenReturn(Optional.empty());

        DecisionApprovalResponse response = service.approve(USER_ID, 20L);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(orderCaptor.capture());
        Order saved = orderCaptor.getValue();
        assertThat(saved.getSide()).isEqualTo(OrderSide.BUY);
        assertThat(saved.getOrderType()).isEqualTo(OrderType.LIMIT);
        assertThat(saved.getQuantity()).isEqualTo(10L);   // 700000 / 70000
        assertThat(saved.getLimitPrice()).isEqualTo(70_000L);
        assertThat(saved.getSource()).isEqualTo(OrderSource.AI_DECISION);

        verify(tradeOrderProducer).publishOrderSubmitted(any());
        assertThat(judgment.getExecutionStatus()).isEqualTo(AiExecutionStatus.READY);
        assertThat(judgment.getOrderId()).isEqualTo(555L);
        assertThat(response.executionStatus()).isEqualTo(AiExecutionStatus.READY);
    }

    @Test
    @DisplayName("approve: 본인 아닌 경우 DECISION_FORBIDDEN")
    void approveByOtherUserForbidden() {
        AiJudgment judgment = buildPending(30L, OffsetDateTime.now().plusMinutes(3));
        when(aiJudgmentRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(judgment));

        assertThatThrownBy(() -> service.approve(999L, 30L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(AiErrorCode.DECISION_FORBIDDEN);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("approve: 이미 처리된 (READY) 판단은 DECISION_NOT_PENDING")
    void approveAlreadyProcessed() {
        AiJudgment judgment = buildPending(40L, OffsetDateTime.now().plusMinutes(3));
        judgment.markApproved(100L);
        when(aiJudgmentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(judgment));

        assertThatThrownBy(() -> service.approve(USER_ID, 40L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(AiErrorCode.DECISION_NOT_PENDING);
    }

    @Test
    @DisplayName("approve: 만료된 row 는 DECISION_EXPIRED")
    void approveExpired() {
        AiJudgment judgment = buildPending(50L, OffsetDateTime.now().minusMinutes(1));
        when(aiJudgmentRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(judgment));

        assertThatThrownBy(() -> service.approve(USER_ID, 50L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(AiErrorCode.DECISION_EXPIRED);
    }

    @Test
    @DisplayName("approve: 존재하지 않는 judgmentId 는 JUDGMENT_NOT_FOUND")
    void approveNotFound() {
        when(aiJudgmentRepository.findByIdForUpdate(60L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(USER_ID, 60L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(AiErrorCode.JUDGMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("reject: judgment.markRejected — Order/Kafka 없음")
    void rejectMarks() {
        AiJudgment judgment = buildPending(70L, OffsetDateTime.now().plusMinutes(3));
        when(aiJudgmentRepository.findByIdForUpdate(70L)).thenReturn(Optional.of(judgment));

        DecisionApprovalResponse response = service.reject(USER_ID, 70L);

        assertThat(judgment.getExecutionStatus()).isEqualTo(AiExecutionStatus.REJECTED);
        assertThat(response.executionStatus()).isEqualTo(AiExecutionStatus.REJECTED);
        verify(orderRepository, never()).saveAndFlush(any());
        verify(tradeOrderProducer, never()).publishOrderSubmitted(any());
    }

    @Test
    @DisplayName("expirePending: 만료 후보 일괄 EXPIRED 전환")
    void expirePendingBulkUpdate() {
        AiJudgment expired1 = buildPending(80L, OffsetDateTime.now().minusMinutes(1));
        AiJudgment expired2 = buildPending(81L, OffsetDateTime.now().minusMinutes(2));
        when(aiJudgmentRepository.findByExecutionStatusInAndApprovalExpiresAtBefore(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(expired1, expired2));

        int count = service.expirePending();

        assertThat(count).isEqualTo(2);
        assertThat(expired1.getExecutionStatus()).isEqualTo(AiExecutionStatus.EXPIRED);
        assertThat(expired2.getExecutionStatus()).isEqualTo(AiExecutionStatus.EXPIRED);
    }

    private AiJudgment buildPending(Long id, OffsetDateTime expiresAt) {
        return buildPendingWithStatus(id, expiresAt, AiExecutionStatus.APPROVAL_REQUIRED);
    }

    /** S14P31B106-354 — RECOMMENDED 행도 동일 흐름 검증용 */
    private AiJudgment buildPendingWithStatus(Long id, OffsetDateTime expiresAt, AiExecutionStatus status) {
        AiJudgment judgment = AiJudgment.builder()
                .userId(USER_ID)
                .stockCode(STOCK)
                .decision("BUY")
                .confidenceScore(78L)
                .indicatorsSnapshot(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())
                .keySignals(new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode())
                .judgmentReason("승인 대기 테스트")
                .judgedAt(OffsetDateTime.now())
                .orderAmount(700_000L)
                .targetPrice(70_000L)
                .stopLossPrice(65_000L)
                .executionStatus(status)
                .stockTier(status == AiExecutionStatus.RECOMMENDED ? 3 : null)
                .matchedRiskGrade(status == AiExecutionStatus.RECOMMENDED ? 4 : null)
                .build();
        judgment.setApprovalExpiresAt(expiresAt);
        ReflectionTestUtils.setField(judgment, "id", id);
        return judgment;
    }

    // ───────────────────────────────────────────────────────────────────
    // S14P31B106-354 — RECOMMENDED 흐름 회귀 방지
    // ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listPending: RECOMMENDED 도 결과에 포함 + stockTier/matchedRiskGrade 노출")
    void listPendingIncludesRecommended() {
        AiJudgment recommended = buildPendingWithStatus(
                30L, OffsetDateTime.now().plusMinutes(3), AiExecutionStatus.RECOMMENDED);
        when(aiJudgmentRepository
                .findByUserIdAndExecutionStatusInOrderByJudgedAtDesc(
                        org.mockito.ArgumentMatchers.eq(USER_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(recommended));

        List<PendingDecisionResponse> result = service.listPending(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).executionStatus()).isEqualTo(AiExecutionStatus.RECOMMENDED);
        assertThat(result.get(0).stockTier()).isEqualTo(3);
        assertThat(result.get(0).matchedRiskGrade()).isEqualTo(4);
    }

    @Test
    @DisplayName("approve: RECOMMENDED row 도 Order INSERT + 승인 처리")
    void approveAcceptsRecommended() {
        AiJudgment recommended = buildPendingWithStatus(
                40L, OffsetDateTime.now().plusMinutes(3), AiExecutionStatus.RECOMMENDED);
        when(aiJudgmentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(recommended));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> {
            Order saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 777L);
            return saved;
        });

        DecisionApprovalResponse response = service.approve(USER_ID, 40L);

        assertThat(recommended.getExecutionStatus()).isEqualTo(AiExecutionStatus.READY);
        assertThat(recommended.getOrderId()).isEqualTo(777L);
        assertThat(response.executionStatus()).isEqualTo(AiExecutionStatus.READY);
        verify(orderRepository).saveAndFlush(any(Order.class));
    }

    @Test
    @DisplayName("reject: RECOMMENDED row 도 REJECTED 전환")
    void rejectAcceptsRecommended() {
        AiJudgment recommended = buildPendingWithStatus(
                41L, OffsetDateTime.now().plusMinutes(3), AiExecutionStatus.RECOMMENDED);
        when(aiJudgmentRepository.findByIdForUpdate(41L)).thenReturn(Optional.of(recommended));

        service.reject(USER_ID, 41L);

        assertThat(recommended.getExecutionStatus()).isEqualTo(AiExecutionStatus.REJECTED);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("expirePending: RECOMMENDED 도 EXPIRED 전환")
    void expirePendingIncludesRecommended() {
        AiJudgment expiredRec = buildPendingWithStatus(
                90L, OffsetDateTime.now().minusMinutes(1), AiExecutionStatus.RECOMMENDED);
        AiJudgment expiredAppr = buildPendingWithStatus(
                91L, OffsetDateTime.now().minusMinutes(2), AiExecutionStatus.APPROVAL_REQUIRED);
        when(aiJudgmentRepository.findByExecutionStatusInAndApprovalExpiresAtBefore(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(expiredRec, expiredAppr));

        int count = service.expirePending();

        assertThat(count).isEqualTo(2);
        assertThat(expiredRec.getExecutionStatus()).isEqualTo(AiExecutionStatus.EXPIRED);
        assertThat(expiredAppr.getExecutionStatus()).isEqualTo(AiExecutionStatus.EXPIRED);
    }
}
