package com.modu.backend.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * KIS API 연동 요청 DTO (POST)
 *
 * accountNo 형식: "계좌번호-상품코드" (예: 50012345-01)
 * 서비스에서 "-" 기준으로 분리해 account_no, account_prdt_cd로 저장
 */
public record KisKeyRegisterRequest(
        @NotBlank String appKey,
        @NotBlank String appSecret,
        @NotBlank String accountNo,
        @NotNull Boolean isRealAccount
) {}
