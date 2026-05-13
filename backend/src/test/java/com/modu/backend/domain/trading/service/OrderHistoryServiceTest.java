package com.modu.backend.domain.trading.service;

import com.modu.backend.domain.trading.client.KisOrderHistoryClient;
import com.modu.backend.domain.trading.client.KisOrderHistoryClient.KisHistoryItem;
import com.modu.backend.domain.trading.dto.OrderHistoryResponse;
import com.modu.backend.domain.trading.entity.HistorySourceFilter;
import com.modu.backend.domain.trading.entity.Order;
import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderSource;
import com.modu.backend.domain.trading.entity.OrderStatus;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.domain.trading.exception.OrderErrorCode;
import com.modu.backend.domain.trading.repository.OrderRepository;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.util.AesGcmEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderHistoryServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock KisCredentialRepository kisCredentialRepository;
    @Mock KisTokenService kisTokenService;
    @Mock KisOrderHistoryClient kisOrderHistoryClient;
    @Mock AesGcmEncryptor encryptor;

    @InjectMocks
    OrderHistoryService orderHistoryService;

    private KisCredential realCredential;
    private KisCredential mockCredential;

    @BeforeEach
    void setUp() {
        realCredential = KisCredential.builder()
                .userId(1L)
                .appKeyEnc("enc-key").appSecretEnc("enc-secret")
                .accountNo("50012345").accountPrdtCd("01")
                .isRealAccount(true)
                .build();
        mockCredential = KisCredential.builder()
                .userId(1L)
                .appKeyEnc("enc-key").appSecretEnc("enc-secret")
                .accountNo("99999999").accountPrdtCd("01")
                .isRealAccount(false)
                .build();

        when(encryptor.decrypt("enc-key")).thenReturn("real-key");
        when(encryptor.decrypt("enc-secret")).thenReturn("real-secret");
        when(kisTokenService.getOrIssueAccessToken(eq(1L), any(), any())).thenReturn("token");
    }

    // ── 검증 실패 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("from > to → HISTORY_INVALID_DATE_RANGE")
    void 기간_역전_예외() {
        LocalDate from = LocalDate.of(2026, 5, 10);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        assertThatThrownBy(() -> orderHistoryService.getOrderHistory(
                1L, HistorySourceFilter.ALL, from, to, 1, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.HISTORY_INVALID_DATE_RANGE));
    }

    @Test
    @DisplayName("기간 1년 초과 → HISTORY_PERIOD_TOO_LONG")
    void 기간_1년_초과_예외() {
        LocalDate to   = LocalDate.of(2026, 5, 12);
        LocalDate from = to.minusMonths(13);

        assertThatThrownBy(() -> orderHistoryService.getOrderHistory(
                1L, HistorySourceFilter.ALL, from, to, 1, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.HISTORY_PERIOD_TOO_LONG));
    }

    @Test
    @DisplayName("KIS 미연동 → KIS_NOT_CONNECTED")
    void 미연동_예외() {
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderHistoryService.getOrderHistory(
                1L, HistorySourceFilter.ALL, null, null, 1, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_NOT_CONNECTED));
    }

    @Test
    @DisplayName("모의투자 계좌 → KIS_MOCK_ACCOUNT_NOT_SUPPORTED")
    void 모의투자_차단() {
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(mockCredential));

        assertThatThrownBy(() -> orderHistoryService.getOrderHistory(
                1L, HistorySourceFilter.ALL, null, null, 1, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_MOCK_ACCOUNT_NOT_SUPPORTED));
    }

    // ── 정상 흐름 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KIS 응답 비었으면 빈 페이지 반환")
    void 빈_응답_빈_페이지() {
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));
        when(kisOrderHistoryClient.getOrderHistory(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        OrderHistoryResponse res = orderHistoryService.getOrderHistory(
                1L, HistorySourceFilter.ALL, null, null, 1, 20);

        assertThat(res.orders()).isEmpty();
        assertThat(res.totalCount()).isZero();
        assertThat(res.page()).isEqualTo(1);
        assertThat(res.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("DB 매칭 항목은 orderId/source/orders.created_at 사용, 미매칭은 KIS 시각 사용")
    void DB_매칭_및_미매칭_병합() {
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));
        KisHistoryItem matched = new KisHistoryItem(
                "ODNO_A", "005930", "삼성전자", "BUY", "LIMIT",
                10L, 70000L, "FILLED", LocalDateTime.of(2026, 5, 1, 10, 0));
        KisHistoryItem unmatched = new KisHistoryItem(
                "ODNO_B", "000660", "SK하이닉스", "SELL", "MARKET",
                5L, 130000L, "CANCELED", LocalDateTime.of(2026, 5, 2, 11, 0));
        when(kisOrderHistoryClient.getOrderHistory(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(matched, unmatched));

        Order dbOrder = buildOrder(100L, "ODNO_A", OrderSource.MANUAL,
                OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.ofHours(9)));
        when(orderRepository.findByUserIdAndKisOrderNoIn(eq(1L), any()))
                .thenReturn(List.of(dbOrder));

        OrderHistoryResponse res = orderHistoryService.getOrderHistory(
                1L, HistorySourceFilter.ALL, null, null, 1, 20);

        assertThat(res.totalCount()).isEqualTo(2);

        // 정렬: 최신순 → ODNO_B(05-02) 가 먼저
        OrderHistoryResponse.OrderHistoryItem first  = res.orders().get(0);
        OrderHistoryResponse.OrderHistoryItem second = res.orders().get(1);

        assertThat(first.orderId()).isNull();
        assertThat(first.source()).isNull();
        assertThat(first.stockCode()).isEqualTo("000660");
        assertThat(first.createdAt()).contains("2026-05-02T11:00:00+09:00");

        assertThat(second.orderId()).isEqualTo("100");
        assertThat(second.source()).isEqualTo("MANUAL");
        assertThat(second.createdAt()).contains("2026-05-01T10:00:00+09:00");
    }

    @Test
    @DisplayName("source=MANUAL 필터 시 DB 미매칭 항목 제외")
    void source_필터_미매칭_제외() {
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));
        KisHistoryItem manual    = new KisHistoryItem("A", "005930", "삼성전자", "BUY", "LIMIT",
                10L, 70000L, "FILLED", LocalDateTime.of(2026, 5, 1, 10, 0));
        KisHistoryItem auto      = new KisHistoryItem("B", "005930", "삼성전자", "BUY", "LIMIT",
                10L, 70000L, "FILLED", LocalDateTime.of(2026, 5, 1, 11, 0));
        KisHistoryItem unmatched = new KisHistoryItem("C", "005930", "삼성전자", "BUY", "LIMIT",
                10L, 70000L, "FILLED", LocalDateTime.of(2026, 5, 1, 12, 0));
        when(kisOrderHistoryClient.getOrderHistory(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(manual, auto, unmatched));

        OffsetDateTime now = OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.ofHours(9));
        when(orderRepository.findByUserIdAndKisOrderNoIn(eq(1L), any()))
                .thenReturn(List.of(
                        buildOrder(1L, "A", OrderSource.MANUAL, now),
                        buildOrder(2L, "B", OrderSource.AI_DECISION, now)
                ));

        OrderHistoryResponse res = orderHistoryService.getOrderHistory(
                1L, HistorySourceFilter.MANUAL, null, null, 1, 20);

        assertThat(res.totalCount()).isEqualTo(1);
        assertThat(res.orders()).hasSize(1);
        assertThat(res.orders().get(0).source()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("페이지네이션 — page=2, size=2 슬라이스")
    void 페이지네이션() {
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(realCredential));
        List<KisHistoryItem> items = List.of(
                new KisHistoryItem("1", "A", "A", "BUY", "LIMIT", 1, 1, "FILLED", LocalDateTime.of(2026, 5, 5, 10, 0)),
                new KisHistoryItem("2", "A", "A", "BUY", "LIMIT", 1, 1, "FILLED", LocalDateTime.of(2026, 5, 4, 10, 0)),
                new KisHistoryItem("3", "A", "A", "BUY", "LIMIT", 1, 1, "FILLED", LocalDateTime.of(2026, 5, 3, 10, 0)),
                new KisHistoryItem("4", "A", "A", "BUY", "LIMIT", 1, 1, "FILLED", LocalDateTime.of(2026, 5, 2, 10, 0)),
                new KisHistoryItem("5", "A", "A", "BUY", "LIMIT", 1, 1, "FILLED", LocalDateTime.of(2026, 5, 1, 10, 0))
        );
        when(kisOrderHistoryClient.getOrderHistory(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(items);
        when(orderRepository.findByUserIdAndKisOrderNoIn(anyLong(), any())).thenReturn(List.of());

        OrderHistoryResponse res = orderHistoryService.getOrderHistory(
                1L, HistorySourceFilter.ALL, null, null, 2, 2);

        assertThat(res.totalCount()).isEqualTo(5);
        assertThat(res.page()).isEqualTo(2);
        assertThat(res.size()).isEqualTo(2);
        // 정렬 후 [1,2,3,4,5] (최신순) → page=2 size=2 → [3,4]
        assertThat(res.orders()).extracting(i -> i.stockCode()).containsExactly("A", "A");
        assertThat(res.orders()).hasSize(2);
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private Order buildOrder(Long id, String kisOrderNo, OrderSource source, OffsetDateTime createdAt) {
        Order order = Order.builder()
                .userId(1L)
                .stockCode("005930")
                .side(OrderSide.BUY)
                .orderType(OrderType.LIMIT)
                .quantity(10L)
                .limitPrice(70000L)
                .status(OrderStatus.FILLED)
                .source(source)
                .idempotencyKey("k-" + id)
                .build();
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "kisOrderNo", kisOrderNo);
        ReflectionTestUtils.setField(order, "createdAt", createdAt);
        return order;
    }
}
