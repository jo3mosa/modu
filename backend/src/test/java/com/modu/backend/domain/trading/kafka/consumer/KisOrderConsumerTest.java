package com.modu.backend.domain.trading.kafka.consumer;

import com.modu.backend.domain.trading.client.KisOrderClient;
import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.entity.OrderStatus;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.domain.trading.exception.OrderErrorCode;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.trading.sse.OrderSseEmitterManager;
import com.modu.backend.domain.trading.sse.OrderSseEvent;
import com.modu.backend.domain.trading.sse.OrderSseEventType;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import com.modu.backend.global.util.AesGcmEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KisOrderConsumerTest {

    @Mock OrderRepository orderRepository;
    @Mock KisCredentialRepository kisCredentialRepository;
    @Mock KisTokenService kisTokenService;
    @Mock KisOrderClient kisOrderClient;
    @Mock AesGcmEncryptor encryptor;
    @Mock OrderSseEmitterManager sseEmitterManager;
    @Mock Acknowledgment ack;

    @InjectMocks KisOrderConsumer consumer;

    private KisCredential credential;

    @BeforeEach
    void setUp() {
        credential = KisCredential.builder()
                .userId(1L)
                .appKeyEnc("enc-key").appSecretEnc("enc-secret")
                .accountNo("50012345").accountPrdtCd("01")
                .isRealAccount(true)
                .build();
        when(encryptor.decrypt("enc-key")).thenReturn("real-key");
        when(encryptor.decrypt("enc-secret")).thenReturn("real-secret");
        when(kisTokenService.getOrIssueAccessToken(eq(1L), any(), any())).thenReturn("token");
    }

    // ── 정상 흐름 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 흐름 — order 갱신 + ORDER_SUBMITTED SSE + ack")
    void 정상_흐름() {
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        Order order = pendingOrder(100L, "uuid-1");
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenReturn(Optional.of(order));
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(credential));
        when(kisOrderClient.placeOrder(any(), any(), any(), any(), any(),
                any(), eq(OrderSide.BUY), eq(OrderType.LIMIT), anyLong(), anyLong()))
                .thenReturn(new KisOrderClient.KisOrderResult("KISODNO1", "KISORG1"));

        consumer.onMessage(msg, ack);

        // order 갱신
        assertThat(order.getKisOrderNo()).isEqualTo("KISODNO1");
        assertThat(order.getKisOrgNo()).isEqualTo("KISORG1");
        // SSE submitted
        ArgumentCaptor<OrderSseEvent> eventCaptor = ArgumentCaptor.forClass(OrderSseEvent.class);
        verify(sseEmitterManager).send(eq(1L), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(OrderSseEventType.ORDER_SUBMITTED);
        assertThat(eventCaptor.getValue().kisOrderNo()).isEqualTo("KISODNO1");
        verify(ack).acknowledge();
    }

    // ── 무시·skip ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("주문 row 없음 — KIS 호출 안 함 + SSE 미발송 + ack")
    void 주문_row_없음() {
        TradeOrderMessage msg = sampleMessage("uuid-x", 1L);
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-x"))
                .thenReturn(Optional.empty());

        consumer.onMessage(msg, ack);

        verify(kisOrderClient, never()).placeOrder(any(), any(), any(), any(), any(),
                any(), any(), any(), anyLong(), anyLong());
        verify(sseEmitterManager, never()).send(any(), any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("이미 처리된 주문(kisOrderNo 보유) — skip + ack")
    void 이미_처리된_주문_skip() {
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        Order order = pendingOrder(100L, "uuid-1");
        order.updateKisInfo("EXISTING", "ORG", OffsetDateTime.now()); // 이미 채워진 상태
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenReturn(Optional.of(order));

        consumer.onMessage(msg, ack);

        verify(kisOrderClient, never()).placeOrder(any(), any(), any(), any(), any(),
                any(), any(), any(), anyLong(), anyLong());
        verify(sseEmitterManager, never()).send(any(), any());
        verify(ack).acknowledge();
    }

    // ── 실패 처리 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KIS 자격증명 없음 — REJECTED + ORDER_FAILED + ack")
    void 자격증명_없음_REJECTED() {
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        Order order = pendingOrder(100L, "uuid-1");
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenReturn(Optional.of(order));
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.empty());

        consumer.onMessage(msg, ack);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectReason()).isEqualTo(UserErrorCode.KIS_NOT_CONNECTED.getDefaultMessage());
        ArgumentCaptor<OrderSseEvent> eventCaptor = ArgumentCaptor.forClass(OrderSseEvent.class);
        verify(sseEmitterManager).send(eq(1L), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(OrderSseEventType.ORDER_FAILED);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("KIS EXTERNAL_API_ERROR → 토큰 재발급 후 재시도 성공")
    void 토큰_재발급_재시도_성공() {
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        Order order = pendingOrder(100L, "uuid-1");
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenReturn(Optional.of(order));
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(credential));
        when(kisOrderClient.placeOrder(any(), any(), any(), any(), any(),
                any(), any(), any(), anyLong(), anyLong()))
                .thenThrow(new ApiException(CommonErrorCode.EXTERNAL_API_ERROR))   // 1차 실패
                .thenReturn(new KisOrderClient.KisOrderResult("KISODNO2", "KISORG2")); // 2차 성공
        when(kisTokenService.issueAndSaveAccessToken(eq(1L), any(), any())).thenReturn("new-token");

        consumer.onMessage(msg, ack);

        verify(kisTokenService).issueAndSaveAccessToken(eq(1L), any(), any());
        verify(kisOrderClient, times(2)).placeOrder(any(), any(), any(), any(), any(),
                any(), any(), any(), anyLong(), anyLong());
        assertThat(order.getKisOrderNo()).isEqualTo("KISODNO2");
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("KIS EXTERNAL_API_ERROR → 토큰 재발급 자체 실패 → KIS_TOKEN_INVALIDATED 로 REJECTED")
    void 토큰_재발급_실패_REJECTED() {
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        Order order = pendingOrder(100L, "uuid-1");
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenReturn(Optional.of(order));
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(credential));
        when(kisOrderClient.placeOrder(any(), any(), any(), any(), any(),
                any(), any(), any(), anyLong(), anyLong()))
                .thenThrow(new ApiException(CommonErrorCode.EXTERNAL_API_ERROR));
        when(kisTokenService.issueAndSaveAccessToken(eq(1L), any(), any()))
                .thenThrow(new ApiException(CommonErrorCode.EXTERNAL_API_ERROR));

        consumer.onMessage(msg, ack);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectReason())
                .isEqualTo(OrderErrorCode.KIS_TOKEN_INVALIDATED.getDefaultMessage());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("KIS 비EXTERNAL_API_ERROR(예: INSUFFICIENT_BALANCE) — 재시도 안 하고 즉시 REJECTED")
    void 비_external_error_즉시_REJECTED() {
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        Order order = pendingOrder(100L, "uuid-1");
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenReturn(Optional.of(order));
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(credential));
        when(kisOrderClient.placeOrder(any(), any(), any(), any(), any(),
                any(), any(), any(), anyLong(), anyLong()))
                .thenThrow(new ApiException(OrderErrorCode.INSUFFICIENT_BALANCE));

        consumer.onMessage(msg, ack);

        // 1회만 호출 (재시도 없음)
        verify(kisOrderClient, times(1)).placeOrder(any(), any(), any(), any(), any(),
                any(), any(), any(), anyLong(), anyLong());
        verify(kisTokenService, never()).issueAndSaveAccessToken(anyLong(), any(), any());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("예외 발생 시에도 ack 가 보장됨 (finally)")
    void 예외_발생시_ack_보장() {
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenThrow(new RuntimeException("DB down"));

        consumer.onMessage(msg, ack);

        verify(ack).acknowledge();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TradeOrderMessage sampleMessage(String orderId, Long userId) {
        return TradeOrderMessage.of(
                orderId, null, userId, "005930",
                OrderSide.BUY, OrderType.LIMIT,
                10L, 70000L, OrderSource.MANUAL, null, OffsetDateTime.now()
        );
    }

    private Order pendingOrder(Long id, String idempotencyKey) {
        Order order = Order.builder()
                .userId(1L)
                .stockCode("005930")
                .side(OrderSide.BUY)
                .orderType(OrderType.LIMIT)
                .quantity(10L)
                .limitPrice(70000L)
                .status(OrderStatus.PENDING)
                .source(OrderSource.MANUAL)
                .idempotencyKey(idempotencyKey)
                .build();
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
