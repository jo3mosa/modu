package com.modu.backend.domain.account.service;

import com.modu.backend.domain.account.client.KisBalanceClient;
import com.modu.backend.domain.account.dto.HoldingResponse;
import com.modu.backend.domain.account.dto.PortfolioResponse;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock KisCredentialRepository kisCredentialRepository;
    @Mock KisTokenService kisTokenService;
    @Mock KisBalanceClient kisBalanceClient;
    @Mock AesGcmEncryptor encryptor;

    @InjectMocks
    PortfolioService portfolioService;

    private KisCredential testCredential;

    @BeforeEach
    void setUp() {
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
    @DisplayName("KIS 미연동 사용자 포트폴리오 조회 시 KIS_NOT_CONNECTED 예외")
    void KIS_미연동_사용자_포트폴리오_조회_시_예외() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> portfolioService.getPortfolio(1L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_NOT_CONNECTED));
    }

    @Test
    @DisplayName("보유 종목이 있는 경우 포트폴리오 조회 성공")
    void 보유_종목_있는_경우_포트폴리오_조회_성공() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(testCredential));
        when(encryptor.decrypt("encrypted-key")).thenReturn("real-app-key");
        when(encryptor.decrypt("encrypted-secret")).thenReturn("real-app-secret");
        when(kisTokenService.getOrIssueAccessToken(1L, "real-app-key", "real-app-secret"))
                .thenReturn("access-token");

        List<HoldingResponse> holdings = List.of(
                new HoldingResponse("005930", "삼성전자", 10L, 75000L, 80000L, 50000L, 6.67)
        );
        PortfolioResponse mockResponse = new PortfolioResponse(holdings);
        when(kisBalanceClient.getPortfolio("access-token", "real-app-key", "real-app-secret",
                "50012345", "01")).thenReturn(mockResponse);

        // when
        PortfolioResponse result = portfolioService.getPortfolio(1L);

        // then
        assertThat(result.holdings()).hasSize(1);
        assertThat(result.holdings().get(0).stockCode()).isEqualTo("005930");
        assertThat(result.holdings().get(0).stockName()).isEqualTo("삼성전자");
        assertThat(result.holdings().get(0).quantity()).isEqualTo(10L);
        assertThat(result.holdings().get(0).pnlPct()).isEqualTo(6.67);
    }

    @Test
    @DisplayName("보유 종목이 없는 경우 빈 목록 반환")
    void 보유_종목_없는_경우_빈_목록_반환() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(testCredential));
        when(encryptor.decrypt("encrypted-key")).thenReturn("real-app-key");
        when(encryptor.decrypt("encrypted-secret")).thenReturn("real-app-secret");
        when(kisTokenService.getOrIssueAccessToken(1L, "real-app-key", "real-app-secret"))
                .thenReturn("access-token");
        when(kisBalanceClient.getPortfolio("access-token", "real-app-key", "real-app-secret",
                "50012345", "01")).thenReturn(new PortfolioResponse(List.of()));

        // when
        PortfolioResponse result = portfolioService.getPortfolio(1L);

        // then
        assertThat(result.holdings()).isEmpty();
    }

    @Test
    @DisplayName("모의계좌로 포트폴리오 조회 시 KIS_MOCK_ACCOUNT_NOT_SUPPORTED 예외")
    void 모의계좌_포트폴리오_조회_시_예외() {
        // given - 모의계좌로 등록된 credential
        KisCredential mockCredential = KisCredential.builder()
                .userId(1L)
                .appKeyEnc("encrypted-key")
                .appSecretEnc("encrypted-secret")
                .accountNo("50012345")
                .accountPrdtCd("01")
                .isRealAccount(false)
                .build();
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(mockCredential));

        // when & then
        assertThatThrownBy(() -> portfolioService.getPortfolio(1L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_MOCK_ACCOUNT_NOT_SUPPORTED));

        verify(kisBalanceClient, never()).getPortfolio(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("복호화 실패 시 KIS_CREDENTIAL_DECRYPT_FAILED 예외")
    void 복호화_실패_시_예외() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(testCredential));
        when(encryptor.decrypt("encrypted-key")).thenThrow(new IllegalStateException("복호화 실패"));

        // when & then
        assertThatThrownBy(() -> portfolioService.getPortfolio(1L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_CREDENTIAL_DECRYPT_FAILED));
    }

    @Test
    @DisplayName("포트폴리오 조회 시 계좌번호와 상품코드가 올바르게 전달")
    void 포트폴리오_조회_시_계좌정보_전달_확인() {
        // given
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(testCredential));
        when(encryptor.decrypt("encrypted-key")).thenReturn("real-app-key");
        when(encryptor.decrypt("encrypted-secret")).thenReturn("real-app-secret");
        when(kisTokenService.getOrIssueAccessToken(1L, "real-app-key", "real-app-secret"))
                .thenReturn("access-token");
        when(kisBalanceClient.getPortfolio("access-token", "real-app-key", "real-app-secret",
                "50012345", "01")).thenReturn(new PortfolioResponse(List.of()));

        // when
        portfolioService.getPortfolio(1L);

        // then
        verify(kisBalanceClient).getPortfolio("access-token", "real-app-key", "real-app-secret",
                "50012345", "01");
    }
}
