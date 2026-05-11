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

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * KisHashKeyClient 단위 테스트
 *
 * [테스트 전략]
 * RETURNS_DEEP_STUBS + 전체 체인 스텁 방식은 setup/execution 시점의 딥 스텁 객체가 달라
 * 스텁이 적중되지 않는 문제가 있다.
 * 대신 첫 번째 호출인 post()에서 바로 throw하도록 스텁하면 프로덕션 코드의
 * try-catch가 동일하게 동작하므로 예외 처리 경로를 올바르게 검증할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class KisHashKeyClientTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient kisRestClient;

    @InjectMocks
    KisHashKeyClient kisHashKeyClient;

    @Test
    @DisplayName("KIS HashKey API 호출 실패 시 EXTERNAL_API_ERROR 예외")
    void hashkey_API_호출_실패_예외() {
        // given
        when(kisRestClient.post()).thenThrow(new RestClientException("connection refused"));

        // when & then
        assertThatThrownBy(() -> kisHashKeyClient.getHashKey("app-key", "app-secret", Map.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR));
    }
}
