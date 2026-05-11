package com.modu.backend.domain.trading.client;

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
import static org.mockito.Mockito.*;

/**
 * KisBuyingPowerClient 단위 테스트
 *
 * [테스트 전략]
 * RestClient 체인 스텁 문제로 첫 번째 호출(get())에서 직접 throw 방식 사용.
 */
@ExtendWith(MockitoExtension.class)
class KisBuyingPowerClientTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient kisRestClient;

    @InjectMocks
    KisBuyingPowerClient kisBuyingPowerClient;

    @Test
    @DisplayName("매수가능조회 API 호출 실패 시 EXTERNAL_API_ERROR 예외")
    void 매수가능조회_API_호출_실패_예외() {
        // given
        when(kisRestClient.get()).thenThrow(new RestClientException("timeout"));

        // when & then
        assertThatThrownBy(() -> kisBuyingPowerClient.getBuyPowerInfo(
                "token", "key", "secret", "50012345", "01", "005930", 75000L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR));
    }

    @Test
    @DisplayName("매도가능수량조회 API 호출 실패 시 EXTERNAL_API_ERROR 예외")
    void 매도가능수량조회_API_호출_실패_예외() {
        // given
        when(kisRestClient.get()).thenThrow(new RestClientException("timeout"));

        // when & then
        assertThatThrownBy(() -> kisBuyingPowerClient.getSellableQuantity(
                "token", "key", "secret", "50012345", "01", "005930"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR));
    }
}
