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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * KisOrderHistoryClient 단위 테스트
 *
 * [테스트 전략]
 * RestClient 딥 스텁 한계로 KIS 호출 실패 경로만 검증한다.
 * 정상 응답 매핑/상태 변환 로직은 OrderHistoryServiceTest 에서 간접 검증.
 */
@ExtendWith(MockitoExtension.class)
class KisOrderHistoryClientTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient kisRestClient;

    @InjectMocks
    KisOrderHistoryClient kisOrderHistoryClient;

    @Test
    @DisplayName("KIS 거래 이력 조회 API 호출 실패 시 EXTERNAL_API_ERROR 예외")
    void 거래이력_조회_API_호출_실패_예외() {
        // given
        when(kisRestClient.get()).thenThrow(new RestClientException("timeout"));

        // when & then
        assertThatThrownBy(() -> kisOrderHistoryClient.getOrderHistory(
                "token", "key", "secret", "50012345", "01",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 5, 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR));
    }
}
