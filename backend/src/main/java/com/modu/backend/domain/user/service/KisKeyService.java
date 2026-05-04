package com.modu.backend.domain.user.service;

import com.modu.backend.domain.user.dto.KisKeyRegisterRequest;
import com.modu.backend.domain.user.dto.KisKeyUpdateRequest;
import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.exception.UserErrorCode;
import com.modu.backend.domain.user.repository.KisCredentialRepository;
import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.util.AesGcmEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KIS API 연동 정보 서비스
 *
 * app_key, app_secret은 AES-256-GCM으로 암호화 후 저장
 * accountNo는 "-" 기준으로 account_no / account_prdt_cd로 분리 저장
 */
@Service
@RequiredArgsConstructor
@Transactional
public class KisKeyService {

    private final KisCredentialRepository kisCredentialRepository;
    private final AesGcmEncryptor encryptor;

    // ── 연동 ──────────────────────────────────────────────────────────────────

    /**
     * KIS API 연동 등록
     *
     * 이미 연동된 경우 KIS_ALREADY_CONNECTED 예외
     * accountNo 형식 검증 후 분리 저장
     */
    public void registerKisKey(Long userId, KisKeyRegisterRequest request) {
        if (kisCredentialRepository.existsByUserId(userId)) {
            throw new ApiException(UserErrorCode.KIS_ALREADY_CONNECTED);
        }

        String[] accountParts = parseAccountNo(request.accountNo());

        try {
            kisCredentialRepository.save(KisCredential.builder()
                    .userId(userId)
                    .appKeyEnc(encryptor.encrypt(request.appKey()))
                    .appSecretEnc(encryptor.encrypt(request.appSecret()))
                    .accountNo(accountParts[0])
                    .accountPrdtCd(accountParts[1])
                    .isRealAccount(request.isRealAccount())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // existsByUserId와 save 사이 동시 요청 race condition 발생 시
            // DB 제약 예외(5xx) 대신 도메인 예외(409)로 변환
            throw new ApiException(UserErrorCode.KIS_ALREADY_CONNECTED);
        }
    }

    // ── 수정 ──────────────────────────────────────────────────────────────────

    /**
     * KIS API 연동 정보 수정
     *
     * 연동 정보가 없으면 KIS_NOT_CONNECTED 예외
     * null이 아닌 필드만 선택적으로 업데이트
     */
    public void updateKisKey(Long userId, KisKeyUpdateRequest request) {
        KisCredential credential = kisCredentialRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(UserErrorCode.KIS_NOT_CONNECTED));

        String[] accountParts = request.accountNo() != null
                ? parseAccountNo(request.accountNo())
                : null;

        credential.update(
                request.appKey() != null ? encryptor.encrypt(request.appKey()) : null,
                request.appSecret() != null ? encryptor.encrypt(request.appSecret()) : null,
                accountParts != null ? accountParts[0] : null,
                accountParts != null ? accountParts[1] : null,
                request.isRealAccount()
        );
    }

    // ── 삭제 ──────────────────────────────────────────────────────────────────

    /**
     * KIS API 연동 해제
     *
     * 연동 정보가 없으면 KIS_NOT_CONNECTED 예외
     */
    public void deleteKisKey(Long userId) {
        if (!kisCredentialRepository.existsByUserId(userId)) {
            throw new ApiException(UserErrorCode.KIS_NOT_CONNECTED);
        }

        kisCredentialRepository.deleteByUserId(userId);
    }

    // ── 내부 유틸 ──────────────────────────────────────────────────────────────

    /**
     * "50012345-01" 형태의 accountNo를 분리
     * 형식 오류 시 INVALID_ACCOUNT_NO_FORMAT 예외
     */
    private String[] parseAccountNo(String accountNo) {
        String[] parts = accountNo.split("-");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new ApiException(UserErrorCode.INVALID_ACCOUNT_NO_FORMAT);
        }
        return parts;
    }
}
