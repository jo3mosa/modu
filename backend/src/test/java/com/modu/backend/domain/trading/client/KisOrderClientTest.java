package com.modu.backend.domain.trading.client;

import com.modu.backend.domain.trading.entity.OrderSide;
import com.modu.backend.domain.trading.entity.OrderType;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * KisOrderClient 단위 테스트
 *
 * [테스트 전략]
 * RETURNS_DEEP_STUBS + 전체 체인 스텁 방식은 setup/execution 시점의 딥 스텁 객체가 달라
 * 스텁이 적중되지 않는 문제가 있다.
 * 대신 첫 번째 호출인 get()/post()에서 바로 throw하도록 스텁하면 프로덕션 코드의
 * try-catch가 동일하게 동작하므로 예외 처리 경로를 올바르게 검증할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class KisOrderClientTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient kisRestClient;

    @Mock
    KisHashKeyClient kisHashKeyClient;

    @InjectMocks
    KisOrderClient kisOrderClient;

    // ── 매수가능조회 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("매수가능조회 API 호출 실패 시 EXTERNAL_API_ERROR 예외")
    void 매수가능조회_API_호출_실패_예외() {
        // given
        when(kisRestClient.get()).thenThrow(new RestClientException("timeout"));

        // when & then
        assertThatThrownBy(() -> kisOrderClient.getBuyableAmount(
                "token", "key", "secret", "50012345", "01", "005930", OrderType.LIMIT, 70000L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR));
    }

    // ── 매도가능수량조회 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("매도가능수량조회 API 호출 실패 시 EXTERNAL_API_ERROR 예외")
    void 매도가능수량조회_API_호출_실패_예외() {
        // given
        when(kisRestClient.get()).thenThrow(new RestClientException("timeout"));

        // when & then
        assertThatThrownBy(() -> kisOrderClient.getSellableQuantity(
                "token", "key", "secret", "50012345", "01", "005930", OrderType.LIMIT, 70000L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR));
    }

    // ── 주문 실행 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("주문 실행 API 호출 실패 시 EXTERNAL_API_ERROR 예외")
    void 주문_실행_API_호출_실패_예외() {
        // given
        when(kisHashKeyClient.getHashKey(anyString(), anyString(), any())).thenReturn("mock-hash");
        when(kisRestClient.post()).thenThrow(new RestClientException("connection refused"));

        // when & then
        assertThatThrownBy(() -> kisOrderClient.placeOrder(
                "token", "key", "secret", "50012345", "01",
                "005930", OrderSide.BUY, OrderType.LIMIT, 10L, 70000L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR));
    }

    @Test
    @DisplayName("HashKey 발급 실패 시 주문 실행 API가 호출되지 않는다")
    void hashkey_발급_실패_시_주문_미실행() {
        // given
        when(kisHashKeyClient.getHashKey(anyString(), anyString(), any()))
                .thenThrow(new ApiException(CommonErrorCode.EXTERNAL_API_ERROR));

        // when & then
        assertThatThrownBy(() -> kisOrderClient.placeOrder(
                "token", "key", "secret", "50012345", "01",
                "005930", OrderSide.BUY, OrderType.LIMIT, 10L, 70000L))
                .isInstanceOf(ApiException.class);

        verify(kisRestClient, never()).post();
    }
}
