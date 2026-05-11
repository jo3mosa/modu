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
 * KisPendingOrderClient 단위 테스트
 *
 * [테스트 전략]
 * RETURNS_DEEP_STUBS + 전체 체인 스텁 방식은 setup/execution 시점의 딥 스텁 객체가 달라
 * 스텁이 적중되지 않는 문제가 있다. (troubleshooting_restclient_deep_stub_mocking.md 참조)
 * 첫 번째 호출인 get()에서 바로 throw하여 예외 처리 경로를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class KisPendingOrderClientTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient kisRestClient;

    @InjectMocks
    KisPendingOrderClient kisPendingOrderClient;

    @Test
    @DisplayName("KIS 미체결 주문 조회 API 호출 실패 시 EXTERNAL_API_ERROR 예외")
    void 미체결_조회_API_호출_실패_예외() {
        // given
        when(kisRestClient.get()).thenThrow(new RestClientException("timeout"));

        // when & then
        assertThatThrownBy(() -> kisPendingOrderClient.getPendingOrders(
                "token", "key", "secret", "50012345", "01"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR));
    }
}
