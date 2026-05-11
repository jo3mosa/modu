package com.modu.backend.domain.trading.service;

import com.modu.backend.domain.trading.client.KisBuyingPowerClient;
import com.modu.backend.domain.trading.client.KisModifyOrderClient;
import com.modu.backend.domain.trading.client.KisOrderClient;
import com.modu.backend.domain.trading.client.KisPendingOrderClient;
import com.modu.backend.domain.trading.dto.BuyingPowerResponse;
import com.modu.backend.domain.trading.dto.ModifyOrderRequest;
import com.modu.backend.domain.trading.dto.ModifyOrderResponse;
import com.modu.backend.domain.trading.dto.OrderRequest;
import com.modu.backend.domain.trading.dto.OrderResponse;
import com.modu.backend.domain.trading.dto.PendingOrdersResponse;
import com.modu.backend.domain.trading.entity.*;
import com.modu.backend.domain.trading.exception.OrderErrorCode;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.trading.repository.TradingRuleRepository;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import com.modu.backend.global.util.AesGcmEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock KisCredentialRepository kisCredentialRepository;
    @Mock TradingRuleRepository tradingRuleRepository;
    @Mock KisTokenService kisTokenService;
    @Mock KisOrderClient kisOrderClient;
    @Mock KisPendingOrderClient kisPendingOrderClient;
    @Mock KisModifyOrderClient kisModifyOrderClient;
    @Mock KisBuyingPowerClient kisBuyingPowerClient;
    @Mock AesGcmEncryptor encryptor;

    @InjectMocks
    OrderService orderService;

    private KisCredential realCredential;
    private KisCredential mockCredential;
    private OrderRequest buyRequest;
    private OrderRequest sellRequest;
    private TradingRule tradingRule;

    // 월요일 10:00 KST — 장 운영 중
    private static final ZonedDateTime MARKET_OPEN =
            ZonedDateTime.of(2026, 5, 12, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
    // 월요일 16:00 KST — 장 마감 이후
    private static final ZonedDateTime MARKET_CLOSED =
            ZonedDateTime.of(2026, 5, 12, 16, 0, 0, 0, ZoneId.of("Asia/Seoul"));
    // 토요일 10:00 KST — 주말
    private static final ZonedDateTime WEEKEND =
            ZonedDateTime.of(2026, 5, 10, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        realCredential = KisCredential.builder()
                .userId(1L)
                .appKeyEnc("enc-key")
                .appSecretEnc("enc-secret")
                .accountNo("50012345")
                .accountPrdtCd("01")
                .isRealAccount(true)
                .build();

        mockCredential = KisCredential.builder()
                .userId(1L)
                .appKeyEnc("enc-key")
                .appSecretEnc("enc-secret")
                .accountNo("99999999")
                .accountPrdtCd("01")
                .isRealAccount(false)
                .build();

        buyRequest  = new OrderRequest("005930", OrderSide.BUY,  10, 70000L, OrderType.LIMIT);
        sellRequest = new OrderRequest("005930", OrderSide.SELL,  5, 70000L, OrderType.LIMIT);

        tradingRule = TradingRule.builder()
                .userId(1L)
                .stopLossPct(5L)
                .takeProfitPct(10L)
                .maxDailyOrderCount(10L)
                .dailyLossLimitAmount(1_000_000L) // 일일 한도 100만원
                .version(1L)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("동일한 Idempotency-Key로 재요청 시 KIS API 호출 없이 기존 응답 반환")
    void 중복_요청_기존_응답_반환() {
        // given
        Order existing = buildOrder(OrderSide.BUY, "dup-key", 1L);
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "dup-key"))
                .thenReturn(Optional.of(existing));

        // when
        OrderResponse response = orderService.placeOrder(1L, buyRequest, "dup-key");

        // then
        assertThat(response.orderId()).isEqualTo("1");
        assertThat(response.status()).isEqualTo("PENDING");
        verify(kisCredentialRepository, never()).findByUserId(any());
        verify(kisOrderClient, never()).placeOrder(
                any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("동시 중복 요청으로 유니크 제약 충돌 시 기존 주문 반환 (KIS 미호출)")
    void 동시_중복_요청_유니크_충돌_기존_주문_반환() {
        // given: 선조회는 empty → credential/잔고 검증 통과 → saveAndFlush 에서 유니크 충돌 → 재조회 반환
        Order existing = buildOrder(OrderSide.BUY, "key-001", 1L);
        when(orderRepository.findByUserIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty())          // 1차 선조회: 없음 (동시 요청 통과)
                .thenReturn(Optional.of(existing));    // 충돌 후 재조회: 기존 주문 반환
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));
        when(tradingRuleRepository.findById(1L)).thenReturn(Optional.empty());

        // saveAndFlush 전 validateBalance 통과를 위한 stubs
        // (decrypt → token → getBuyableAmount 순으로 호출됨)
        when(encryptor.decrypt("enc-key")).thenReturn("real-key");
        when(encryptor.decrypt("enc-secret")).thenReturn("real-secret");
        when(kisTokenService.getOrIssueAccessToken(1L, "real-key", "real-secret")).thenReturn("token");
        when(kisOrderClient.getBuyableAmount(any(), any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(10_000_000L);

        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value"));

        try (MockedStatic<ZonedDateTime> mocked =
                     mockStatic(ZonedDateTime.class, Answers.CALLS_REAL_METHODS)) {
            mocked.when(() -> ZonedDateTime.now(any(ZoneId.class))).thenReturn(MARKET_OPEN);

            // when
            OrderResponse response = orderService.placeOrder(1L, buyRequest, "key-001");

            // then: 기존 주문 응답 반환, KIS 주문 미실행
            assertThat(response.orderId()).isEqualTo("1");
            verify(kisOrderClient, never()).placeOrder(
                    any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong());
        }
    }

    @Test
    @DisplayName("Idempotency-Key 미전송 시 서버에서 UUID 자동 생성 후 주문 진행")
    void idempotencyKey_null_자동_생성() {
        // given
        setupKisStubs(OrderSide.BUY);
        when(tradingRuleRepository.findById(1L)).thenReturn(Optional.empty());

        try (MockedStatic<ZonedDateTime> mocked =
                     mockStatic(ZonedDateTime.class, Answers.CALLS_REAL_METHODS)) {
            mocked.when(() -> ZonedDateTime.now(any(ZoneId.class))).thenReturn(MARKET_OPEN);

            // when: idempotencyKey = null
            OrderResponse response = orderService.placeOrder(1L, buyRequest, null);

            // then: 주문이 정상적으로 처리됨 (UUID 자동 생성)
            assertThat(response).isNotNull();
            verify(orderRepository).saveAndFlush(argThat(order ->
                    order.getIdempotencyKey() != null && !order.getIdempotencyKey().isBlank()));
        }
    }

    // ── KIS 연동 검증 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("KIS 미연동 사용자 주문 시 KIS_NOT_CONNECTED 예외")
    void KIS_미연동_주문_예외() {
        // given
        when(orderRepository.findByUserIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.placeOrder(1L, buyRequest, "key-001"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_NOT_CONNECTED));
    }

    @Test
    @DisplayName("모의투자 계좌로 주문 시 KIS_MOCK_ACCOUNT_NOT_SUPPORTED 예외")
    void 모의투자_계좌_주문_예외() {
        // given
        when(orderRepository.findByUserIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(mockCredential));

        // when & then
        assertThatThrownBy(() -> orderService.placeOrder(1L, buyRequest, "key-001"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_MOCK_ACCOUNT_NOT_SUPPORTED));
    }

    // ── 장 운영 시간 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("장 마감 시간(15:30 이후) 주문 시 MARKET_CLOSED 예외")
    void 장_마감_시간_주문_예외() {
        // given
        when(orderRepository.findByUserIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));

        try (MockedStatic<ZonedDateTime> mocked =
                     mockStatic(ZonedDateTime.class, Answers.CALLS_REAL_METHODS)) {
            mocked.when(() -> ZonedDateTime.now(any(ZoneId.class))).thenReturn(MARKET_CLOSED);

            // when & then
            assertThatThrownBy(() -> orderService.placeOrder(1L, buyRequest, "key-001"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(OrderErrorCode.MARKET_CLOSED));
        }
    }

    @Test
    @DisplayName("주말에 주문 시 MARKET_CLOSED 예외")
    void 주말_주문_예외() {
        // given
        when(orderRepository.findByUserIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));

        try (MockedStatic<ZonedDateTime> mocked =
                     mockStatic(ZonedDateTime.class, Answers.CALLS_REAL_METHODS)) {
            mocked.when(() -> ZonedDateTime.now(any(ZoneId.class))).thenReturn(WEEKEND);

            // when & then
            assertThatThrownBy(() -> orderService.placeOrder(1L, buyRequest, "key-001"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(OrderErrorCode.MARKET_CLOSED));
        }
    }

    // ── 일일 한도 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("일일 누적 매수 금액 한도 초과 시 DAILY_ORDER_LIMIT_EXCEEDED 예외")
    void 일일_한도_초과_예외() {
        // given: 오늘 이미 950,000원 매수 / 신규 주문 10 × 70,000 = 700,000 → 합산 1,650,000 > 1,000,000
        when(orderRepository.findByUserIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));
        when(tradingRuleRepository.findById(1L)).thenReturn(Optional.of(tradingRule));
        when(orderRepository.sumTodayBuyAmount(1L)).thenReturn(950_000L);

        try (MockedStatic<ZonedDateTime> mocked =
                     mockStatic(ZonedDateTime.class, Answers.CALLS_REAL_METHODS)) {
            mocked.when(() -> ZonedDateTime.now(any(ZoneId.class))).thenReturn(MARKET_OPEN);

            // when & then
            assertThatThrownBy(() -> orderService.placeOrder(1L, buyRequest, "key-001"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(OrderErrorCode.DAILY_ORDER_LIMIT_EXCEEDED));
        }
    }

    @Test
    @DisplayName("매도 주문은 일일 누적 한도 체크를 하지 않는다")
    void 매도_주문_한도_체크_미적용() {
        // given
        setupKisStubs(OrderSide.SELL);

        try (MockedStatic<ZonedDateTime> mocked =
                     mockStatic(ZonedDateTime.class, Answers.CALLS_REAL_METHODS)) {
            mocked.when(() -> ZonedDateTime.now(any(ZoneId.class))).thenReturn(MARKET_OPEN);

            // when
            orderService.placeOrder(1L, sellRequest, "key-001");

            // then: 매도는 sumTodayBuyAmount 호출 없음
            verify(orderRepository, never()).sumTodayBuyAmount(anyLong());
        }
    }

    @Test
    @DisplayName("TradingRule 미설정 사용자는 일일 한도 체크를 생략하고 주문이 진행된다")
    void 룰_미설정_한도_체크_생략() {
        // given
        setupKisStubs(OrderSide.BUY);
        when(tradingRuleRepository.findById(1L)).thenReturn(Optional.empty());

        try (MockedStatic<ZonedDateTime> mocked =
                     mockStatic(ZonedDateTime.class, Answers.CALLS_REAL_METHODS)) {
            mocked.when(() -> ZonedDateTime.now(any(ZoneId.class))).thenReturn(MARKET_OPEN);

            // when
            orderService.placeOrder(1L, buyRequest, "key-001");

            // then: 룰 없으면 sumTodayBuyAmount 호출 없음
            verify(orderRepository, never()).sumTodayBuyAmount(anyLong());
        }
    }

    // ── 잔고 검증 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("매수 주문 금액이 매수 가능 금액 초과 시 INSUFFICIENT_BALANCE 예외")
    void 매수_잔고_부족_예외() {
        // given: 주문 금액 700,000 > 매수가능금액 500,000
        when(orderRepository.findByUserIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));
        when(tradingRuleRepository.findById(1L)).thenReturn(Optional.of(tradingRule));
        when(orderRepository.sumTodayBuyAmount(1L)).thenReturn(0L);
        when(encryptor.decrypt("enc-key")).thenReturn("real-key");
        when(encryptor.decrypt("enc-secret")).thenReturn("real-secret");
        when(kisTokenService.getOrIssueAccessToken(1L, "real-key", "real-secret")).thenReturn("token");
        when(kisOrderClient.getBuyableAmount(any(), any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(500_000L);

        try (MockedStatic<ZonedDateTime> mocked =
                     mockStatic(ZonedDateTime.class, Answers.CALLS_REAL_METHODS)) {
            mocked.when(() -> ZonedDateTime.now(any(ZoneId.class))).thenReturn(MARKET_OPEN);

            // when & then
            assertThatThrownBy(() -> orderService.placeOrder(1L, buyRequest, "key-001"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(OrderErrorCode.INSUFFICIENT_BALANCE));
        }
    }

    @Test
    @DisplayName("매도 주문 수량이 매도 가능 수량 초과 시 INSUFFICIENT_BALANCE 예외")
    void 매도_수량_부족_예외() {
        // given: 주문 수량 5주 > 매도가능수량 3주
        when(orderRepository.findByUserIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));
        when(encryptor.decrypt("enc-key")).thenReturn("real-key");
        when(encryptor.decrypt("enc-secret")).thenReturn("real-secret");
        when(kisTokenService.getOrIssueAccessToken(1L, "real-key", "real-secret")).thenReturn("token");
        when(kisOrderClient.getSellableQuantity(any(), any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(3L);

        try (MockedStatic<ZonedDateTime> mocked =
                     mockStatic(ZonedDateTime.class, Answers.CALLS_REAL_METHODS)) {
            mocked.when(() -> ZonedDateTime.now(any(ZoneId.class))).thenReturn(MARKET_OPEN);

            // when & then
            assertThatThrownBy(() -> orderService.placeOrder(1L, sellRequest, "key-001"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                            .isEqualTo(OrderErrorCode.INSUFFICIENT_BALANCE));
        }
    }

    // ── 주문 성공 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("매수 주문 성공 - status=PENDING, source=MANUAL, kisOrderNo/kisOrgNo 저장 확인")
    void 매수_주문_성공() {
        // given
        setupKisStubs(OrderSide.BUY);
        when(tradingRuleRepository.findById(1L)).thenReturn(Optional.of(tradingRule));
        when(orderRepository.sumTodayBuyAmount(1L)).thenReturn(0L);

        try (MockedStatic<ZonedDateTime> mocked =
                     mockStatic(ZonedDateTime.class, Answers.CALLS_REAL_METHODS)) {
            mocked.when(() -> ZonedDateTime.now(any(ZoneId.class))).thenReturn(MARKET_OPEN);

            // when
            OrderResponse response = orderService.placeOrder(1L, buyRequest, "key-001");

            // then
            assertThat(response.orderId()).isEqualTo("1");
            assertThat(response.stockCode()).isEqualTo("005930");
            assertThat(response.side()).isEqualTo("BUY");
            assertThat(response.quantity()).isEqualTo(10);
            assertThat(response.price()).isEqualTo(70000L);
            assertThat(response.status()).isEqualTo("PENDING");

            // saveAndFlush 시점엔 KIS 미호출이므로 kisOrderNo/kisOrgNo 는 null
            // KIS 응답 후 updateKisInfo()로 반영됨
            verify(orderRepository).saveAndFlush(argThat(order ->
                    order.getStatus() == OrderStatus.PENDING &&
                    order.getSource() == OrderSource.MANUAL  &&
                    order.getSide()   == OrderSide.BUY
            ));
        }
    }

    @Test
    @DisplayName("매도 주문 성공 - status=PENDING, source=MANUAL 저장 확인")
    void 매도_주문_성공() {
        // given
        setupKisStubs(OrderSide.SELL);

        try (MockedStatic<ZonedDateTime> mocked =
                     mockStatic(ZonedDateTime.class, Answers.CALLS_REAL_METHODS)) {
            mocked.when(() -> ZonedDateTime.now(any(ZoneId.class))).thenReturn(MARKET_OPEN);

            // when
            OrderResponse response = orderService.placeOrder(1L, sellRequest, "key-002");

            // then
            assertThat(response.side()).isEqualTo("SELL");
            assertThat(response.quantity()).isEqualTo(5);
            verify(orderRepository).saveAndFlush(argThat(order ->
                    order.getSide()   == OrderSide.SELL   &&
                    order.getStatus() == OrderStatus.PENDING
            ));
        }
    }

    // ── 미체결 주문 조회 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("KIS 미연동 사용자 미체결 조회 시 KIS_NOT_CONNECTED 예외")
    void 미체결_조회_KIS_미연동_예외() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.getPendingOrders(1L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_NOT_CONNECTED));
    }

    @Test
    @DisplayName("모의투자 계좌로 미체결 조회 시 KIS_MOCK_ACCOUNT_NOT_SUPPORTED 예외")
    void 미체결_조회_모의투자_계좌_예외() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(mockCredential));

        // when & then
        assertThatThrownBy(() -> orderService.getPendingOrders(1L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_MOCK_ACCOUNT_NOT_SUPPORTED));
    }

    @Test
    @DisplayName("KIS 미체결 없을 때 빈 리스트 반환")
    void 미체결_주문_없을_때_빈_리스트_반환() {
        // given
        setupKisCredentialStubs();
        when(kisPendingOrderClient.getPendingOrders(any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        // when
        PendingOrdersResponse response = orderService.getPendingOrders(1L);

        // then
        assertThat(response.pendingOrders()).isEmpty();
        verify(orderRepository, never()).findByUserIdAndKisOrderNoIn(anyLong(), any());
    }

    @Test
    @DisplayName("미체결 주문 조회 성공 - KIS 데이터와 DB 메타데이터 병합 확인")
    void 미체결_주문_조회_성공_병합() {
        // given: KIS 미체결 주문 1건, DB 매칭 주문 1건
        KisPendingOrderClient.KisPendingItem kisItem = new KisPendingOrderClient.KisPendingItem(
                "KIS_ORD001", "005930", "삼성전자",
                "BUY", "LIMIT",
                10L, 82000L, 3L, 7L
        );
        Order dbOrder = buildOrder(OrderSide.BUY, "idem-key", 1L);
        ReflectionTestUtils.setField(dbOrder, "kisOrderNo", "KIS_ORD001");

        setupKisCredentialStubs();
        when(kisPendingOrderClient.getPendingOrders(any(), any(), any(), any(), any()))
                .thenReturn(List.of(kisItem));
        when(orderRepository.findByUserIdAndKisOrderNoIn(eq(1L), anyList()))
                .thenReturn(List.of(dbOrder));

        // when
        PendingOrdersResponse response = orderService.getPendingOrders(1L);

        // then
        assertThat(response.pendingOrders()).hasSize(1);
        PendingOrdersResponse.PendingOrderItem item = response.pendingOrders().get(0);

        // KIS 실시간 데이터 검증
        assertThat(item.stockCode()).isEqualTo("005930");
        assertThat(item.stockName()).isEqualTo("삼성전자");
        assertThat(item.side()).isEqualTo("BUY");
        assertThat(item.orderType()).isEqualTo("LIMIT");
        assertThat(item.quantity()).isEqualTo(10);
        assertThat(item.price()).isEqualTo(82000L);
        assertThat(item.filledQuantity()).isEqualTo(3);
        assertThat(item.remainQuantity()).isEqualTo(7);

        // DB 메타데이터 검증
        assertThat(item.orderId()).isEqualTo("1");
        assertThat(item.source()).isEqualTo("MANUAL");
        assertThat(item.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("KIS 주문이 DB 미매칭 시 orderId/source/createdAt 은 null 로 반환")
    void 미체결_주문_DB_미매칭_null_필드_반환() {
        // given: KIS 미체결 주문 1건, DB 매칭 없음
        KisPendingOrderClient.KisPendingItem kisItem = new KisPendingOrderClient.KisPendingItem(
                "EXTERNAL_ORD", "005930", "삼성전자",
                "BUY", "LIMIT",
                5L, 80000L, 0L, 5L
        );

        setupKisCredentialStubs();
        when(kisPendingOrderClient.getPendingOrders(any(), any(), any(), any(), any()))
                .thenReturn(List.of(kisItem));
        when(orderRepository.findByUserIdAndKisOrderNoIn(eq(1L), anyList()))
                .thenReturn(Collections.emptyList()); // DB 매칭 없음

        // when
        PendingOrdersResponse response = orderService.getPendingOrders(1L);

        // then: KIS 데이터는 정상, DB 의존 필드는 null
        assertThat(response.pendingOrders()).hasSize(1);
        PendingOrdersResponse.PendingOrderItem item = response.pendingOrders().get(0);
        assertThat(item.stockCode()).isEqualTo("005930");
        assertThat(item.orderId()).isNull();
        assertThat(item.source()).isNull();
        assertThat(item.createdAt()).isNull();
    }

    @Test
    @DisplayName("KIS 응답 수량 필드가 int 범위 초과 시 EXTERNAL_API_ERROR 예외")
    void 미체결_주문_수량_int_overflow_예외() {
        // given: quantity가 Integer.MAX_VALUE 초과 (toIntExact overflow 유발)
        long overflowValue = (long) Integer.MAX_VALUE + 1;
        KisPendingOrderClient.KisPendingItem kisItem = new KisPendingOrderClient.KisPendingItem(
                "KIS_ORD001", "005930", "삼성전자",
                "BUY", "LIMIT",
                overflowValue, 80000L, 0L, 5L  // quantity overflow
        );

        setupKisCredentialStubs();
        when(kisPendingOrderClient.getPendingOrders(any(), any(), any(), any(), any()))
                .thenReturn(List.of(kisItem));
        when(orderRepository.findByUserIdAndKisOrderNoIn(eq(1L), anyList()))
                .thenReturn(Collections.emptyList());

        // when & then: (int) 직접 캐스팅 시 잘못된 음수가 아닌 EXTERNAL_API_ERROR 발생
        assertThatThrownBy(() -> orderService.getPendingOrders(1L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR));
    }

    // ── 미체결 주문 정정/취소 ─────────────────────────────────────────────────

    @Test
    @DisplayName("주문 없음 시 ORDER_NOT_FOUND 예외")
    void 정정_취소_주문_없음_예외() {
        // given
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.modifyOrCancelOrder(
                1L, 99L, new ModifyOrderRequest(OrderModifyAction.CANCEL, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    @DisplayName("타인 주문 정정/취소 시 ORDER_FORBIDDEN 예외")
    void 정정_취소_타인_주문_예외() {
        // given: 주문의 userId=2, 요청 userId=1
        Order otherOrder = buildOrder(OrderSide.BUY, "key", 1L);
        ReflectionTestUtils.setField(otherOrder, "userId", 2L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(otherOrder));

        // when & then
        assertThatThrownBy(() -> orderService.modifyOrCancelOrder(
                1L, 1L, new ModifyOrderRequest(OrderModifyAction.CANCEL, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.ORDER_FORBIDDEN));
    }

    @Test
    @DisplayName("FILLED 상태 주문 정정 시 ORDER_ALREADY_FILLED 예외")
    void 정정_체결된_주문_예외() {
        // given
        Order filledOrder = buildOrder(OrderSide.BUY, "key", 1L);
        ReflectionTestUtils.setField(filledOrder, "status", OrderStatus.FILLED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(filledOrder));

        // when & then
        assertThatThrownBy(() -> orderService.modifyOrCancelOrder(
                1L, 1L, new ModifyOrderRequest(OrderModifyAction.MODIFY, 5, 65000L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.ORDER_ALREADY_FILLED));
    }

    @Test
    @DisplayName("주문 취소 성공 - status=CANCELED, cancelled_at 기록")
    void 주문_취소_성공() {
        // given
        Order order = buildOrder(OrderSide.BUY, "key", 1L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        setupKisCredentialStubs();
        when(kisModifyOrderClient.execute(any(), any(), any(), any(), any(),
                any(), any(), any(), eq(OrderModifyAction.CANCEL), anyLong(), anyLong(), any(), any()))
                .thenReturn(new KisModifyOrderClient.KisModifyResult("NEW_ORD", "NEW_ORG"));

        // when
        ModifyOrderResponse response = orderService.modifyOrCancelOrder(
                1L, 1L, new ModifyOrderRequest(OrderModifyAction.CANCEL, null, null));

        // then
        assertThat(response.status()).isEqualTo("CANCELED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(order.getCancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("주문 정정 성공 - status=MODIFIED, 새 kis_order_no/kis_org_no 갱신")
    void 주문_정정_성공() {
        // given
        Order order = buildOrder(OrderSide.BUY, "key", 1L);
        ReflectionTestUtils.setField(order, "kisOrderNo", "OLD_ORD");
        ReflectionTestUtils.setField(order, "kisOrgNo",   "OLD_ORG");
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        setupKisCredentialStubs();
        when(kisModifyOrderClient.execute(any(), any(), any(), any(), any(),
                any(), any(), any(), eq(OrderModifyAction.MODIFY), anyLong(), anyLong(), any(), any()))
                .thenReturn(new KisModifyOrderClient.KisModifyResult("NEW_ORD", "NEW_ORG"));

        // when
        ModifyOrderResponse response = orderService.modifyOrCancelOrder(
                1L, 1L, new ModifyOrderRequest(OrderModifyAction.MODIFY, 5, 65000L));

        // then
        assertThat(response.status()).isEqualTo("MODIFIED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.MODIFIED);
        // 재정정을 위해 새 KIS 번호 갱신 확인
        assertThat(order.getKisOrderNo()).isEqualTo("NEW_ORD");
        assertThat(order.getKisOrgNo()).isEqualTo("NEW_ORG");
        // 변경된 가격/수량 반영 확인
        assertThat(order.getLimitPrice()).isEqualTo(65000L);
        assertThat(order.getQuantity()).isEqualTo(5L);
    }

    @Test
    @DisplayName("정정 시 newKisOrderNo가 null이면 기존 kis_order_no 보존")
    void 정정_새_KIS_번호_null_시_기존_번호_보존() {
        // given: KisModifyOrderClient가 null 번호를 반환하는 엣지 케이스
        Order order = buildOrder(OrderSide.BUY, "key", 1L);
        ReflectionTestUtils.setField(order, "kisOrderNo", "ORIGINAL_ORD");
        ReflectionTestUtils.setField(order, "kisOrgNo",   "ORIGINAL_ORG");
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        setupKisCredentialStubs();
        when(kisModifyOrderClient.execute(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyLong(), anyLong(), any(), any()))
                .thenReturn(new KisModifyOrderClient.KisModifyResult(null, null));

        // when
        orderService.modifyOrCancelOrder(
                1L, 1L, new ModifyOrderRequest(OrderModifyAction.MODIFY, 5, 65000L));

        // then: null 전달 시 기존 KIS 번호 덮어쓰지 않음
        assertThat(order.getKisOrderNo()).isEqualTo("ORIGINAL_ORD");
        assertThat(order.getKisOrgNo()).isEqualTo("ORIGINAL_ORG");
    }

    @Test
    @DisplayName("MODIFIED 상태 주문도 재정정 가능")
    void 정정된_주문_재정정_가능() {
        // given: 이미 정정된(MODIFIED) 주문
        Order modifiedOrder = buildOrder(OrderSide.BUY, "key", 1L);
        ReflectionTestUtils.setField(modifiedOrder, "status", OrderStatus.MODIFIED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(modifiedOrder));
        setupKisCredentialStubs();
        when(kisModifyOrderClient.execute(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyLong(), anyLong(), any(), any()))
                .thenReturn(new KisModifyOrderClient.KisModifyResult("NEW_ORD2", "NEW_ORG2"));

        // when & then: 예외 없이 정상 처리
        assertThatCode(() -> orderService.modifyOrCancelOrder(
                1L, 1L, new ModifyOrderRequest(OrderModifyAction.MODIFY, 3, 60000L)))
                .doesNotThrowAnyException();
    }

    // ── 주문 가능 금액/수량 조회 ──────────────────────────────────────────────

    @Test
    @DisplayName("side=SELL 이고 stockCode=null 이면 ORDER_002 예외")
    void 주문가능조회_SELL_종목코드_없음_예외() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));

        // when & then
        assertThatThrownBy(() -> orderService.getBuyingPower(1L, null, OrderSide.SELL, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.SELL_REQUIRES_STOCK_CODE));
    }

    @Test
    @DisplayName("KIS 미연동 사용자 buying-power 조회 시 KIS_NOT_CONNECTED 예외")
    void 주문가능조회_KIS_미연동_예외() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.getBuyingPower(1L, "005930", OrderSide.BUY, 75000L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_NOT_CONNECTED));
    }

    @Test
    @DisplayName("모의투자 계좌로 buying-power 조회 시 KIS_MOCK_ACCOUNT_NOT_SUPPORTED 예외")
    void 주문가능조회_모의투자_계좌_예외() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(mockCredential));

        // when & then
        assertThatThrownBy(() -> orderService.getBuyingPower(1L, "005930", OrderSide.BUY, 75000L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_MOCK_ACCOUNT_NOT_SUPPORTED));
    }

    @Test
    @DisplayName("side=BUY 조회 성공 - maxSellQuantity=0, getSellableQuantity 미호출")
    void 주문가능조회_BUY_성공() {
        // given
        setupKisCredentialStubs();
        when(kisBuyingPowerClient.getBuyPowerInfo(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new KisBuyingPowerClient.KisBuyPowerInfo(1_000_000L, 13L, 2_000_000L));

        // when
        BuyingPowerResponse response = orderService.getBuyingPower(1L, "005930", OrderSide.BUY, 75000L);

        // then
        assertThat(response.maxBuyAmount()).isEqualTo(1_000_000L);
        assertThat(response.maxBuyQuantity()).isEqualTo(13);
        assertThat(response.maxSellQuantity()).isEqualTo(0);
        assertThat(response.availableCash()).isEqualTo(2_000_000L);
        verify(kisBuyingPowerClient, never()).getSellableQuantity(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("side=SELL 조회 성공 - getSellableQuantity 호출, maxBuyQuantity=0")
    void 주문가능조회_SELL_성공() {
        // given
        setupKisCredentialStubs();
        when(kisBuyingPowerClient.getBuyPowerInfo(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new KisBuyingPowerClient.KisBuyPowerInfo(1_000_000L, 13L, 2_000_000L));
        when(kisBuyingPowerClient.getSellableQuantity(any(), any(), any(), any(), any(), any()))
                .thenReturn(50L);

        // when
        BuyingPowerResponse response = orderService.getBuyingPower(1L, "005930", OrderSide.SELL, null);

        // then: SELL 시 maxBuyQuantity=0 (API 계약)
        assertThat(response.maxSellQuantity()).isEqualTo(50);
        assertThat(response.maxBuyQuantity()).isEqualTo(0);
        verify(kisBuyingPowerClient).getSellableQuantity(any(), any(), any(), any(), any(), eq("005930"));
    }

    @Test
    @DisplayName("orderPrice=null 시 SELL도 정상 조회 (가격 파라미터 불필요)")
    void 주문가능조회_orderPrice_null_SELL_성공() {
        // given
        setupKisCredentialStubs();
        when(kisBuyingPowerClient.getBuyPowerInfo(any(), any(), any(), any(), any(), any(), isNull()))
                .thenReturn(new KisBuyingPowerClient.KisBuyPowerInfo(500_000L, 6L, 1_000_000L));
        when(kisBuyingPowerClient.getSellableQuantity(any(), any(), any(), any(), any(), any()))
                .thenReturn(30L);

        // when
        BuyingPowerResponse response = orderService.getBuyingPower(1L, "005930", OrderSide.SELL, null);

        // then
        assertThat(response.maxSellQuantity()).isEqualTo(30);
        assertThat(response.availableCash()).isEqualTo(1_000_000L);
    }

    // ── 헬퍼 메서드 ───────────────────────────────────────────────────────────

    /**
     * KIS 자격증명 + 토큰 공통 stub (미체결 조회 등 단순 조회에 사용)
     */
    private void setupKisCredentialStubs() {
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));
        when(encryptor.decrypt("enc-key")).thenReturn("real-key");
        when(encryptor.decrypt("enc-secret")).thenReturn("real-secret");
        when(kisTokenService.getOrIssueAccessToken(1L, "real-key", "real-secret")).thenReturn("token");
    }

    /**
     * 주문 성공 흐름에 필요한 공통 stub 설정
     */
    private void setupKisStubs(OrderSide side) {
        when(orderRepository.findByUserIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));
        when(encryptor.decrypt("enc-key")).thenReturn("real-key");
        when(encryptor.decrypt("enc-secret")).thenReturn("real-secret");
        when(kisTokenService.getOrIssueAccessToken(1L, "real-key", "real-secret")).thenReturn("token");

        if (side == OrderSide.BUY) {
            when(kisOrderClient.getBuyableAmount(any(), any(), any(), any(), any(), any(), anyLong()))
                    .thenReturn(10_000_000L);
        } else {
            when(kisOrderClient.getSellableQuantity(any(), any(), any(), any(), any(), any(), anyLong()))
                    .thenReturn(100L);
        }

        when(kisOrderClient.placeOrder(
                any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(new KisOrderClient.KisOrderResult("ORD001", "ORG001"));

        // 서비스가 saveAndFlush를 사용하므로 saveAndFlush 스텁
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            ReflectionTestUtils.setField(o, "id", 1L);
            return o;
        });
    }

    private Order buildOrder(OrderSide side, String idempotencyKey, Long id) {
        Order order = Order.builder()
                .userId(1L)
                .stockCode("005930")
                .side(side)
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
