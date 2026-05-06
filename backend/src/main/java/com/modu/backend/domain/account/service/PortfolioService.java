package com.modu.backend.domain.account.service;

import com.modu.backend.domain.account.client.KisBalanceClient;
import com.modu.backend.domain.account.dto.PortfolioResponse;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 사용자 포트폴리오 서비스
 *
 * [처리 흐름]
 * 1. KIS 연동 정보 조회 (없으면 KIS_NOT_CONNECTED)
 * 2. 실전투자 계좌 여부 검증 (모의계좌면 KIS_MOCK_ACCOUNT_NOT_SUPPORTED)
 * 3. appKey/appSecret 복호화 (실패 시 KIS_CREDENTIAL_DECRYPT_FAILED)
 * 4. 유효 토큰 조회 또는 재발급
 * 5. KIS 주식잔고조회 API 호출
 *
 * [트랜잭션 설계]
 * 클래스 레벨 @Transactional 미사용
 * - 토큰 쓰기: KisTokenService 자체 @Transactional에서 처리
 * - 외부 HTTP 호출: 트랜잭션 밖에서 실행해 DB 커넥션 점유 방지
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final KisCredentialRepository kisCredentialRepository;
    private final KisTokenService kisTokenService;
    private final KisBalanceClient kisBalanceClient;
    private final AesGcmEncryptor encryptor;

    public PortfolioResponse getPortfolio(Long userId) {
        KisCredential credential = kisCredentialRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(UserErrorCode.KIS_NOT_CONNECTED));

        // TTTC8434R는 실전투자 전용 — 모의계좌 사용 시 KIS에서 불명확한 오류 반환
        if (!credential.isRealAccount()) {
            throw new ApiException(UserErrorCode.KIS_MOCK_ACCOUNT_NOT_SUPPORTED);
        }

        String decryptedAppKey;
        String decryptedAppSecret;
        try {
            decryptedAppKey = encryptor.decrypt(credential.getAppKeyEnc());
            decryptedAppSecret = encryptor.decrypt(credential.getAppSecretEnc());
        } catch (IllegalStateException e) {
            log.error("KIS 자격증명 복호화 실패 - userId: {}", userId, e);
            throw new ApiException(UserErrorCode.KIS_CREDENTIAL_DECRYPT_FAILED);
        }

        String accessToken = kisTokenService.getOrIssueAccessToken(userId, decryptedAppKey, decryptedAppSecret);

        return kisBalanceClient.getPortfolio(
                accessToken,
                decryptedAppKey,
                decryptedAppSecret,
                credential.getAccountNo(),
                credential.getAccountPrdtCd()
        );
    }
}
