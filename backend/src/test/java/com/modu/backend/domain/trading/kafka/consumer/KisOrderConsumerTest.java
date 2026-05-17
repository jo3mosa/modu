package com.modu.backend.domain.trading.kafka.consumer;

import com.modu.backend.domain.strategy.service.KillSwitchService;
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
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.kafka.dto.TradeOrderMessage;
import com.modu.backend.global.kis.KisApiCallTemplate;
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
    @Mock KisApiCallTemplate kisApiCallTemplate;
    @Mock KisOrderClient kisOrderClient;
    @Mock AesGcmEncryptor encryptor;
    @Mock OrderSseEmitterManager sseEmitterManager;
    @Mock KillSwitchService killSwitchService;
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
        when(kisApiCallTemplate.callWithTokenRetry(eq(1L), any(), any(), any()))
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
    @DisplayName("KIS 호출 성공 (template 내부 토큰 재시도까지 추상화)")
    void kis_호출_성공_template_추상화() {
        // KisApiCallTemplate 의 토큰 재발급 + 재시도 로직은 KisApiCallTemplate 의 단위 테스트 책임.
        // Consumer 단위 테스트는 template 가 성공 결과를 반환하면 정상 흐름 진입함을 검증.
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        Order order = pendingOrder(100L, "uuid-1");
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenReturn(Optional.of(order));
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(credential));
        when(kisApiCallTemplate.callWithTokenRetry(eq(1L), any(), any(), any()))
                .thenReturn(new KisOrderClient.KisOrderResult("KISODNO2", "KISORG2"));

        consumer.onMessage(msg, ack);

        assertThat(order.getKisOrderNo()).isEqualTo("KISODNO2");
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("KIS template 가 KIS_TOKEN_INVALIDATED 최종 실패 → REJECTED + ORDER_FAILED")
    void 토큰_재발급_실패_REJECTED() {
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        Order order = pendingOrder(100L, "uuid-1");
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenReturn(Optional.of(order));
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(credential));
        when(kisApiCallTemplate.callWithTokenRetry(eq(1L), any(), any(), any()))
                .thenThrow(new ApiException(UserErrorCode.KIS_TOKEN_INVALIDATED));

        consumer.onMessage(msg, ack);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectReason())
                .isEqualTo(UserErrorCode.KIS_TOKEN_INVALIDATED.getDefaultMessage());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("KIS 비-토큰 에러(예: INSUFFICIENT_BALANCE) — REJECTED + ORDER_FAILED")
    void 비_external_error_즉시_REJECTED() {
        TradeOrderMessage msg = sampleMessage("uuid-1", 1L);
        Order order = pendingOrder(100L, "uuid-1");
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "uuid-1"))
                .thenReturn(Optional.of(order));
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(credential));
        when(kisApiCallTemplate.callWithTokenRetry(eq(1L), any(), any(), any()))
                .thenThrow(new ApiException(OrderErrorCode.INSUFFICIENT_BALANCE));

        consumer.onMessage(msg, ack);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectReason())
                .isEqualTo(OrderErrorCode.INSUFFICIENT_BALANCE.getDefaultMessage());
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
