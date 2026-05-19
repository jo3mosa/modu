package com.modu.backend.domain.account.service;

import com.modu.backend.domain.account.client.KisAssetClient;
import com.modu.backend.domain.account.dto.AccountSummaryResponse;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.kis.KisApiCallTemplate;
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

import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceTest {

    @Mock KisCredentialRepository kisCredentialRepository;
    @Mock KisTokenService kisTokenService;
    @Mock KisApiCallTemplate kisApiCallTemplate;
    @Mock KisAssetClient kisAssetClient;
    @Mock AesGcmEncryptor encryptor;

    @InjectMocks
    AccountService accountService;

    private KisCredential testCredential;

    @BeforeEach
    void setUp() {
        when(kisApiCallTemplate.callWithTokenRetry(anyLong(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Function<String, Object> fn = invocation.getArgument(3);
                    return fn.apply("access-token");
                });

        testCredential = KisCredential.builder()
                .userId(1L)
                .appKeyEnc("encrypted-key")
                .appSecretEnc("encrypted-secret")
                .accountNo("50012345")
                .accountPrdtCd("01")
                .isRealAccount(true)
                .build();
    }

    @Test
    @DisplayName("KIS 미연동 사용자 자산 조회 시 KIS_NOT_CONNECTED 예외")
    void KIS_미연동_사용자_자산_조회_시_예외() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> accountService.getAssetSummary(1L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_NOT_CONNECTED));
    }

    @Test
    @DisplayName("자산 조회 성공 - appKey/appSecret 복호화 후 KIS API 호출")
    void 자산_조회_성공() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(testCredential));
        when(encryptor.decrypt("encrypted-key")).thenReturn("real-app-key");
        when(encryptor.decrypt("encrypted-secret")).thenReturn("real-app-secret");
        when(kisTokenService.getOrIssueAccessToken(1L, "real-app-key", "real-app-secret"))
                .thenReturn("access-token");

        AccountSummaryResponse mockResponse = new AccountSummaryResponse(
                15000000L, 5000000L, 10000000L, 9000000L, 1000000L, 11.11);
        when(kisAssetClient.getAssetSummary("access-token", "real-app-key", "real-app-secret",
                "50012345", "01")).thenReturn(mockResponse);

        // when
        AccountSummaryResponse result = accountService.getAssetSummary(1L);

        // then
        assertThat(result.totalAsset()).isEqualTo(15000000L);
        assertThat(result.availableCash()).isEqualTo(5000000L);
        assertThat(result.totalPnlPct()).isEqualTo(11.11);

        verify(encryptor).decrypt("encrypted-key");
        verify(encryptor).decrypt("encrypted-secret");
        // 토큰 발급은 KisApiCallTemplate.callWithTokenRetry 내부 책임 — KisTokenService 직접 verify 제거
        verify(kisAssetClient).getAssetSummary("access-token", "real-app-key", "real-app-secret",
                "50012345", "01");
    }

    @Test
    @DisplayName("자산 조회 시 계좌번호와 상품코드가 올바르게 전달되는지 확인")
    void 자산_조회_시_계좌정보_전달_확인() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(testCredential));
        when(encryptor.decrypt("encrypted-key")).thenReturn("real-app-key");
        when(encryptor.decrypt("encrypted-secret")).thenReturn("real-app-secret");
        when(kisTokenService.getOrIssueAccessToken(1L, "real-app-key", "real-app-secret"))
                .thenReturn("access-token");
        when(kisAssetClient.getAssetSummary("access-token", "real-app-key", "real-app-secret",
                "50012345", "01"))
                .thenReturn(new AccountSummaryResponse(0L, 0L, 0L, 0L, 0L, 0.0));

        // when
        accountService.getAssetSummary(1L);

        // then - 계좌번호 앞 8자리, 상품코드 "01" 전달 확인
        verify(kisAssetClient).getAssetSummary("access-token", "real-app-key", "real-app-secret",
                "50012345", "01");
    }
}
