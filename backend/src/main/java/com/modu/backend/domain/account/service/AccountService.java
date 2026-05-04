package com.modu.backend.domain.account.service;

import com.modu.backend.domain.account.client.KisAssetClient;
import com.modu.backend.domain.account.dto.AccountSummaryResponse;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.domain.user.service.KisTokenService;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 사용자 계좌 자산 서비스
 *
 * [처리 흐름]
 * 1. KIS 연동 정보 조회 (없으면 KIS_NOT_CONNECTED)
 * 2. appKey/appSecret 복호화
 * 3. 유효 토큰 조회 또는 재발급
 * 4. KIS 자산 조회 API 호출
 *
 * [트랜잭션 설계]
 * 클래스 레벨 @Transactional 미사용.
 * - DB 읽기: 트랜잭션 없이도 동작 (단순 조회)
 * - 토큰 쓰기: KisTokenService 자체 @Transactional에서 처리
 * - 외부 HTTP 호출: 트랜잭션 밖에서 실행해 DB 커넥션 점유 방지
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final KisCredentialRepository kisCredentialRepository;
    private final KisTokenService kisTokenService;
    private final KisAssetClient kisAssetClient;
    private final AesGcmEncryptor encryptor;

    public AccountSummaryResponse getAssetSummary(Long userId) {
        KisCredential credential = kisCredentialRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(UserErrorCode.KIS_NOT_CONNECTED));

        String decryptedAppKey = encryptor.decrypt(credential.getAppKeyEnc());
        String decryptedAppSecret = encryptor.decrypt(credential.getAppSecretEnc());

        String accessToken = kisTokenService.getOrIssueAccessToken(userId, decryptedAppKey, decryptedAppSecret);

        return kisAssetClient.getAssetSummary(
                accessToken,
                decryptedAppKey,
                decryptedAppSecret,
                credential.getAccountNo(),
                credential.getAccountPrdtCd()
        );
    }
}
