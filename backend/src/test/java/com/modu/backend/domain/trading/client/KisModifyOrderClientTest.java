package com.modu.backend.domain.trading.client;

import com.modu.backend.domain.trading.entity.OrderModifyAction;
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
 * KisModifyOrderClient 단위 테스트
 *
 * [테스트 전략]
 * RestClient 체인 스텁 문제로 첫 번째 호출(post())에서 직접 throw 방식 사용.
 * HashKey 발급 실패 케이스는 KisHashKeyClient Mock으로 검증.
 */
@ExtendWith(MockitoExtension.class)
class KisModifyOrderClientTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient kisRestClient;

    @Mock
    KisHashKeyClient kisHashKeyClient;

    @InjectMocks
    KisModifyOrderClient kisModifyOrderClient;

    @Test
    @DisplayName("KIS 정정/취소 API 호출 실패 시 EXTERNAL_API_ERROR 예외")
    void 정정_취소_API_호출_실패_예외() {
        // given
        when(kisHashKeyClient.getHashKey(anyString(), anyString(), any())).thenReturn("mock-hash");
        when(kisRestClient.post()).thenThrow(new RestClientException("connection refused"));

        // when & then
        assertThatThrownBy(() -> kisModifyOrderClient.execute(
                "token", "key", "secret", "50012345", "01",
                "org-no", "order-no", OrderType.LIMIT, OrderModifyAction.MODIFY,
                10L, 70000L, 5, 65000L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR));
    }

    @Test
    @DisplayName("HashKey 발급 실패 시 KIS 주문 정정/취소 API 미호출")
    void hashkey_발급_실패_시_정정_취소_미실행() {
        // given
        when(kisHashKeyClient.getHashKey(anyString(), anyString(), any()))
                .thenThrow(new ApiException(CommonErrorCode.EXTERNAL_API_ERROR));

        // when & then
        assertThatThrownBy(() -> kisModifyOrderClient.execute(
                "token", "key", "secret", "50012345", "01",
                "org-no", "order-no", OrderType.LIMIT, OrderModifyAction.CANCEL,
                10L, 70000L, null, null))
                .isInstanceOf(ApiException.class);

        verify(kisRestClient, never()).post();
    }
}
