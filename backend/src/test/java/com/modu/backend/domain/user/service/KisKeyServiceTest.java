package com.modu.backend.domain.user.service;

import com.modu.backend.domain.user.dto.KisKeyRegisterRequest;
import com.modu.backend.domain.user.dto.KisKeyUpdateRequest;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.util.AesGcmEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KisKeyServiceTest {

    @Mock KisCredentialRepository kisCredentialRepository;
    @Mock KisTokenService kisTokenService;
    @Mock AesGcmEncryptor encryptor;

    @InjectMocks
    KisKeyService kisKeyService;

    private KisCredential testCredential;

    @BeforeEach
    void setUp() {
        testCredential = KisCredential.builder()
                .userId(1L)
                .appKeyEnc("encrypted-app-key")
                .appSecretEnc("encrypted-app-secret")
                .accountNo("50012345")
                .accountPrdtCd("01")
                .isRealAccount(true)
                .build();
    }

    // ── 연동 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KIS API 연동 성공 - 토큰 즉시 발급 및 자격증명 암호화 저장")
    void KIS_연동_성공() {
        // given
        KisKeyRegisterRequest request = new KisKeyRegisterRequest(
                "real-app-key", "real-app-secret", "50012345-01", true);
        when(kisCredentialRepository.existsByUserId(1L)).thenReturn(false);
        when(encryptor.encrypt("real-app-key")).thenReturn("encrypted-key");
        when(encryptor.encrypt("real-app-secret")).thenReturn("encrypted-secret");

        // when
        kisKeyService.registerKisKey(1L, request);

        // then
        verify(kisTokenService).issueAndSaveAccessToken(1L, "real-app-key", "real-app-secret");
        verify(kisTokenService).issueAndSaveWebSocketKey(1L, "real-app-key", "real-app-secret");
        verify(kisCredentialRepository).save(any(KisCredential.class));
    }

    @Test
    @DisplayName("이미 연동된 사용자가 재연동 시 KIS_ALREADY_CONNECTED 예외")
    void 중복_연동_시_예외() {
        // given
        KisKeyRegisterRequest request = new KisKeyRegisterRequest(
                "real-app-key", "real-app-secret", "50012345-01", true);
        when(kisCredentialRepository.existsByUserId(1L)).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> kisKeyService.registerKisKey(1L, request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_ALREADY_CONNECTED));

        verify(kisCredentialRepository, never()).save(any());
    }

    @Test
    @DisplayName("잘못된 계좌번호 형식으로 연동 시 INVALID_ACCOUNT_NO_FORMAT 예외")
    void 잘못된_계좌번호_형식_시_예외() {
        // given
        KisKeyRegisterRequest request = new KisKeyRegisterRequest(
                "real-app-key", "real-app-secret", "5001234501", true); // "-" 없음
        when(kisCredentialRepository.existsByUserId(1L)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> kisKeyService.registerKisKey(1L, request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.INVALID_ACCOUNT_NO_FORMAT));
    }

    @Test
    @DisplayName("계좌번호 형식이 '-' 로만 구성된 경우 INVALID_ACCOUNT_NO_FORMAT 예외")
    void 빈_계좌번호_형식_시_예외() {
        // given
        KisKeyRegisterRequest request = new KisKeyRegisterRequest(
                "real-app-key", "real-app-secret", "-", true);
        when(kisCredentialRepository.existsByUserId(1L)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> kisKeyService.registerKisKey(1L, request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.INVALID_ACCOUNT_NO_FORMAT));
    }

    // ── 수정 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("연동 정보 수정 성공 - 요청한 필드만 업데이트")
    void KIS_수정_성공() {
        // given
        KisKeyUpdateRequest request = new KisKeyUpdateRequest(null, "new-secret", null, null);
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(testCredential));
        when(encryptor.encrypt("new-secret")).thenReturn("encrypted-new-secret");

        // when
        kisKeyService.updateKisKey(1L, request);

        // then
        verify(encryptor).encrypt("new-secret");
        verify(encryptor, never()).encrypt("real-app-key"); // appKey는 암호화 안 해야 함
    }

    @Test
    @DisplayName("연동 정보 없을 때 수정 시 KIS_NOT_CONNECTED 예외")
    void 연동_없을_때_수정_시_예외() {
        // given
        KisKeyUpdateRequest request = new KisKeyUpdateRequest("new-key", null, null, null);
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> kisKeyService.updateKisKey(1L, request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_NOT_CONNECTED));
    }

    @Test
    @DisplayName("수정 요청에서 accountNo 포함 시 형식 검증")
    void 수정_시_잘못된_계좌번호_형식_예외() {
        // given
        KisKeyUpdateRequest request = new KisKeyUpdateRequest(null, null, "잘못된형식", null);
        when(kisCredentialRepository.findByUserId(1L)).thenReturn(Optional.of(testCredential));

        // when & then
        assertThatThrownBy(() -> kisKeyService.updateKisKey(1L, request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.INVALID_ACCOUNT_NO_FORMAT));
    }

    // ── 삭제 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KIS API 연동 해제 성공")
    void KIS_연동_해제_성공() {
        // given
        when(kisCredentialRepository.existsByUserId(1L)).thenReturn(true);

        // when
        kisKeyService.deleteKisKey(1L);

        // then
        verify(kisCredentialRepository).deleteByUserId(1L);
    }

    @Test
    @DisplayName("연동 정보 없을 때 해제 시 KIS_NOT_CONNECTED 예외")
    void 연동_없을_때_해제_시_예외() {
        // given
        when(kisCredentialRepository.existsByUserId(1L)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> kisKeyService.deleteKisKey(1L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.KIS_NOT_CONNECTED));

        verify(kisCredentialRepository, never()).deleteByUserId(any());
    }
}
