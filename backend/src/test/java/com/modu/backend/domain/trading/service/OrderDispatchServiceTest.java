package com.modu.backend.domain.trading.service;

import com.modu.backend.domain.strategy.service.KillSwitchService;
import com.modu.backend.domain.trading.calendar.policy.MarketHourPhase;
import com.modu.backend.domain.trading.calendar.policy.MarketHourPolicy;
import com.modu.backend.domain.trading.client.KisOrderClient;
import com.modu.backend.domain.trading.client.KisReservedOrderClient;
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
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.kis.KisApiCallTemplate;
import com.modu.backend.global.util.AesGcmEncryptor;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderDispatchService — MarketHourPhase 별 분기 + 모의계좌 차단 검증.
 *
 * KIS 실 호출은 모두 mock. 본 테스트는 라우팅 / 상태 전이 / SSE / Kill Switch 호출 여부만 확인.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderDispatchServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock KisCredentialRepository kisCredentialRepository;
    @Mock AesGcmEncryptor encryptor;
    @Mock KisApiCallTemplate kisApiCallTemplate;
    @Mock KisOrderClient kisOrderClient;
    @Mock KisReservedOrderClient kisReservedOrderClient;
    @Mock OrderSseEmitterManager sseEmitterManager;
    @Mock KillSwitchService killSwitchService;
    @Mock MarketHourPolicy marketHourPolicy;

    @InjectMocks
    OrderDispatchService service;

    private static final Long ORDER_ID = 100L;
    private static final Long USER_ID = 1L;
    private static final String STOCK = "005930";

    @BeforeEach
    void setUp() {
        when(encryptor.decrypt(anyString())).thenReturn("decrypted");
        // KisApiCallTemplate 는 단순히 콜백을 그대로 실행하도록 stub (토큰 재시도 로직 별도 검증)
        when(kisApiCallTemplate.callWithTokenRetry(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    Function<String, ?> fn = inv.getArgument(3);
                    return fn.apply("test-token");
                });
    }

    // ──────────────────────────────────────────────────────────────────
    // 분기 1: REGULAR — 일반 주문
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("REGULAR + AI 매매 → placeOrder + SSE submitted + Kill Switch recordSuccess")
    void regularPhase_aiDecision() {
        Order order = orderPending(OrderSource.AI_DECISION);
        givenOrder(order, realCredential());
        when(marketHourPolicy.classify(any())).thenReturn(MarketHourPhase.REGULAR);
        when(kisOrderClient.placeOrder(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(new KisOrderClient.KisOrderResult("ORD123", "ORG456"));

        service.dispatch(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING); // updateKisInfo 는 status 그대로
        assertThat(order.getKisOrderNo()).isEqualTo("ORD123");
        verify(killSwitchService).recordSuccess(eq(USER_ID), eq(STOCK));
        verify(sseEmitterManager).send(eq(USER_ID), any());
    }

    @Test
    @DisplayName("REGULAR + MANUAL 성공 → recordSuccess 호출 X (수동 주문은 Kill Switch 카운트 제외)")
    void regularPhase_manual_success() {
        Order order = orderPending(OrderSource.MANUAL);
        givenOrder(order, realCredential());
        when(marketHourPolicy.classify(any())).thenReturn(MarketHourPhase.REGULAR);
        when(kisOrderClient.placeOrder(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(new KisOrderClient.KisOrderResult("ORDM1", "ORGM1"));

        service.dispatch(ORDER_ID);

        assertThat(order.getKisOrderNo()).isEqualTo("ORDM1");
        verify(killSwitchService, never()).recordSuccess(anyLong(), anyString());
        verify(sseEmitterManager).send(eq(USER_ID), any());
    }

    @Test
    @DisplayName("REGULAR + 자격증명 없음 → REJECTED + ORDER_FAILED SSE")
    void regularPhase_credentialNotFound() {
        Order order = orderPending(OrderSource.MANUAL);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(kisCredentialRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(marketHourPolicy.classify(any())).thenReturn(MarketHourPhase.REGULAR);

        service.dispatch(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectReason()).isEqualTo(UserErrorCode.KIS_NOT_CONNECTED.getDefaultMessage());
        ArgumentCaptor<OrderSseEvent> eventCaptor = ArgumentCaptor.forClass(OrderSseEvent.class);
        verify(sseEmitterManager).send(eq(USER_ID), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(OrderSseEventType.ORDER_FAILED);
        // 인프라성 — Kill Switch 카운트 제외
        verify(killSwitchService, never()).recordReject(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("REGULAR + 자격증명 복호화 실패 → REJECTED + Kill Switch 카운트 X")
    void regularPhase_credentialDecryptFail() {
        Order order = orderPending(OrderSource.AI_DECISION);
        givenOrder(order, realCredential());
        when(marketHourPolicy.classify(any())).thenReturn(MarketHourPhase.REGULAR);
        when(encryptor.decrypt(anyString()))
                .thenThrow(new IllegalStateException("decrypt failed"));

        service.dispatch(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectReason())
                .isEqualTo(UserErrorCode.KIS_CREDENTIAL_DECRYPT_FAILED.getDefaultMessage());
        verify(killSwitchService, never()).recordReject(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("REGULAR + 토큰 재발급 실패(KIS_TOKEN_INVALIDATED) → REJECTED + ORDER_FAILED + KillSwitch X (인증 인프라 장애)")
    void regularPhase_tokenInvalidated() {
        Order order = orderPending(OrderSource.AI_DECISION);
        givenOrder(order, realCredential());
        when(marketHourPolicy.classify(any())).thenReturn(MarketHourPhase.REGULAR);
        // setUp 의 thenAnswer stub 을 doThrow 로 덮어쓴다 (when().thenThrow() 는 thenAnswer 람다를 평가해 NPE)
        doThrow(new ApiException(UserErrorCode.KIS_TOKEN_INVALIDATED))
                .when(kisApiCallTemplate).callWithTokenRetry(anyLong(), anyString(), anyString(), any());

        service.dispatch(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectReason())
                .isEqualTo(UserErrorCode.KIS_TOKEN_INVALIDATED.getDefaultMessage());
        ArgumentCaptor<OrderSseEvent> eventCaptor = ArgumentCaptor.forClass(OrderSseEvent.class);
        verify(sseEmitterManager).send(eq(USER_ID), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(OrderSseEventType.ORDER_FAILED);
        // 토큰 실패는 KIS 응답성 거부 아님 → Kill Switch 카운트 제외
        verify(killSwitchService, never()).recordReject(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("REGULAR + MANUAL + INSUFFICIENT_BALANCE → REJECTED + Kill Switch 카운트 X (수동)")
    void regularPhase_manual_kisReject() {
        Order order = orderPending(OrderSource.MANUAL);
        givenOrder(order, realCredential());
        when(marketHourPolicy.classify(any())).thenReturn(MarketHourPhase.REGULAR);
        doThrow(new ApiException(OrderErrorCode.INSUFFICIENT_BALANCE))
                .when(kisApiCallTemplate).callWithTokenRetry(anyLong(), anyString(), anyString(), any());

        service.dispatch(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectReason())
                .isEqualTo(OrderErrorCode.INSUFFICIENT_BALANCE.getDefaultMessage());
        ArgumentCaptor<OrderSseEvent> eventCaptor = ArgumentCaptor.forClass(OrderSseEvent.class);
        verify(sseEmitterManager).send(eq(USER_ID), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(OrderSseEventType.ORDER_FAILED);
        // MANUAL source — Kill Switch 카운트 제외
        verify(killSwitchService, never()).recordReject(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("REGULAR + AI 매매 + INSUFFICIENT_BALANCE → REJECTED + Kill Switch recordReject")
    void regularPhase_aiDecision_kisReject() {
        Order order = orderPending(OrderSource.AI_DECISION);
        givenOrder(order, realCredential());
        when(marketHourPolicy.classify(any())).thenReturn(MarketHourPhase.REGULAR);
        doThrow(new ApiException(OrderErrorCode.INSUFFICIENT_BALANCE))
                .when(kisApiCallTemplate).callWithTokenRetry(anyLong(), anyString(), anyString(), any());

        service.dispatch(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        verify(killSwitchService).recordReject(eq(USER_ID), eq(STOCK), anyString());
    }

    // ──────────────────────────────────────────────────────────────────
    // 분기 2: RESERVED_AVAILABLE — 실 계좌 vs 모의
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RESERVED_AVAILABLE + 실계좌 + AI 매매 → placeReservedOrder + status=RESERVED + recordSuccess")
    void reservedAvailable_realAccount() {
        Order order = orderPending(OrderSource.AI_DECISION);
        givenOrder(order, realCredential());
        when(marketHourPolicy.classify(any())).thenReturn(MarketHourPhase.RESERVED_AVAILABLE);
        when(kisReservedOrderClient.placeReservedOrder(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(new KisReservedOrderClient.KisReservedOrderResult("RSVN789"));

        service.dispatch(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
        assertThat(order.getKisRsvnSeq()).isEqualTo("RSVN789");
        verify(killSwitchService).recordSuccess(eq(USER_ID), eq(STOCK));
    }

    @Test
    @DisplayName("RESERVED_AVAILABLE + 모의계좌 → REJECTED + Kill Switch 카운트 X")
    void reservedAvailable_mockAccount() {
        Order order = orderPending(OrderSource.AI_DECISION);
        givenOrder(order, mockCredential());
        when(marketHourPolicy.classify(any())).thenReturn(MarketHourPhase.RESERVED_AVAILABLE);

        service.dispatch(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectReason()).contains("모의계좌");
        verify(killSwitchService, never()).recordReject(anyLong(), anyString(), anyString());
        verify(kisReservedOrderClient, never()).placeReservedOrder(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    // ──────────────────────────────────────────────────────────────────
    // 분기 3: WAITING_FOR_RESERVED_WINDOW
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("WAITING + 실계좌 → RESERVED_PENDING + SSE reservedPending")
    void waiting_realAccount() {
        Order order = orderPending(OrderSource.AI_DECISION);
        givenOrder(order, realCredential());
        when(marketHourPolicy.classify(any())).thenReturn(MarketHourPhase.WAITING_FOR_RESERVED_WINDOW);

        service.dispatch(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED_PENDING);
        verify(kisOrderClient, never()).placeOrder(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong());
        verify(kisReservedOrderClient, never()).placeReservedOrder(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("WAITING + 이미 RESERVED_PENDING → noop (재진입 가드)")
    void waiting_alreadyPending_noop() {
        Order order = orderPending(OrderSource.AI_DECISION);
        order.markReservedPending(); // 이미 대기 중
        givenOrder(order, realCredential());
        when(marketHourPolicy.classify(any())).thenReturn(MarketHourPhase.WAITING_FOR_RESERVED_WINDOW);

        service.dispatch(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED_PENDING);
        // SSE reservedPending 은 첫 전이 시에만 — 재진입에서는 발송 안 함
        verify(sseEmitterManager, never()).send(anyLong(), any());
    }

    // ──────────────────────────────────────────────────────────────────
    // 분기 4: REJECT 시간대
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("REJECT 시간대 + AI 매매 → REJECTED + Kill Switch 카운트 X (인프라성 reject)")
    void rejectPhase() {
        Order order = orderPending(OrderSource.AI_DECISION);
        givenOrder(order, realCredential());
        when(marketHourPolicy.classify(any())).thenReturn(MarketHourPhase.REJECT);

        service.dispatch(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        verify(killSwitchService, never()).recordReject(anyLong(), anyString(), anyString());
    }

    // ──────────────────────────────────────────────────────────────────
    // 분기 5: 종착 상태 self-skip
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("이미 RESERVED → skip (중복 dispatch 가드)")
    void terminalSkip() {
        Order order = orderPending(OrderSource.AI_DECISION);
        order.markReserved("RSVN_OLD", java.time.OffsetDateTime.now());
        givenOrder(order, realCredential());

        service.dispatch(ORDER_ID);

        // 분기 자체가 호출되지 않음 (credential 도 조회 X)
        verify(marketHourPolicy, never()).classify(any());
        verify(kisOrderClient, never()).placeOrder(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    // ──────────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────────

    private void givenOrder(Order order, KisCredential credential) {
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(kisCredentialRepository.findByUserId(USER_ID)).thenReturn(Optional.of(credential));
    }

    private static Order orderPending(OrderSource source) {
        return Order.builder()
                .userId(USER_ID)
                .stockCode(STOCK)
                .side(OrderSide.BUY)
                .orderType(OrderType.LIMIT)
                .quantity(1L)
                .limitPrice(70000L)
                .status(OrderStatus.PENDING)
                .source(source)
                .idempotencyKey("test-idem-key")
                .build();
    }

    private static KisCredential realCredential() {
        return KisCredential.builder()
                .userId(USER_ID)
                .appKeyEnc("encKey").appSecretEnc("encSecret")
                .accountNo("12345678").accountPrdtCd("01")
                .isRealAccount(true)
                .build();
    }

    private static KisCredential mockCredential() {
        return KisCredential.builder()
                .userId(USER_ID)
                .appKeyEnc("encKey").appSecretEnc("encSecret")
                .accountNo("12345678").accountPrdtCd("01")
                .isRealAccount(false)
                .build();
    }
}
