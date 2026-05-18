package com.modu.backend.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 한국투자증권 API 연동 정보 엔티티 (kis_credentials 테이블)
 *
 * - user_id가 PK (사용자당 하나의 KIS 계정만 허용)
 * - app_key, app_secret, hts_id 는 AES-256-GCM으로 암호화해 저장
 * - accountNo는 "50012345-01" 형태를 account_no / account_prdt_cd로 분리 저장
 */
@Entity
@Table(name = "kis_credentials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KisCredential {

    @Id
    @Column(name = "user_id")
    private Long userId;

    /** AES-256-GCM 암호화된 App Key */
    @Column(name = "app_key_enc", nullable = false)
    private String appKeyEnc;

    /** AES-256-GCM 암호화된 App Secret */
    @Column(name = "app_secret_enc", nullable = false)
    private String appSecretEnc;

    /** AES-256-GCM 암호화된 HTS ID — 체결통보(H0STCNI0) WS SUBSCRIBE 의 tr_key */
    @Column(name = "hts_id_enc")
    private String htsIdEnc;

    /** 계좌번호 앞 8자리 */
    @Column(name = "account_no", nullable = false)
    private String accountNo;

    /** 계좌 상품 코드 (일반 주식: 01) */
    @Column(name = "account_prdt_cd", nullable = false, length = 2)
    private String accountPrdtCd;

    @Column(name = "is_real_account", nullable = false)
    private boolean isRealAccount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    public KisCredential(Long userId, String appKeyEnc, String appSecretEnc, String htsIdEnc,
                         String accountNo, String accountPrdtCd, boolean isRealAccount) {
        this.userId = userId;
        this.appKeyEnc = appKeyEnc;
        this.appSecretEnc = appSecretEnc;
        this.htsIdEnc = htsIdEnc;
        this.accountNo = accountNo;
        this.accountPrdtCd = accountPrdtCd;
        this.isRealAccount = isRealAccount;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public void update(String appKeyEnc, String appSecretEnc, String htsIdEnc,
                       String accountNo, String accountPrdtCd, Boolean isRealAccount) {
        if (appKeyEnc != null) this.appKeyEnc = appKeyEnc;
        if (appSecretEnc != null) this.appSecretEnc = appSecretEnc;
        if (htsIdEnc != null) this.htsIdEnc = htsIdEnc;
        if (accountNo != null) this.accountNo = accountNo;
        if (accountPrdtCd != null) this.accountPrdtCd = accountPrdtCd;
        if (isRealAccount != null) this.isRealAccount = isRealAccount;
        this.updatedAt = OffsetDateTime.now();
    }
}
