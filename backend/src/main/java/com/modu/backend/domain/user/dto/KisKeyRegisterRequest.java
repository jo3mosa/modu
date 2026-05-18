package com.modu.backend.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "한국투자증권 API 연동 요청")
public record KisKeyRegisterRequest(
        @Schema(description = "한국투자증권 App Key", example = "PSxxx...")
        @NotBlank String appKey,

        @Schema(description = "한국투자증권 App Secret", example = "yZxxx...")
        @NotBlank String appSecret,

        @Schema(description = "KIS HTS 로그인 ID (체결통보 WS 구독에 필수)", example = "myhts01")
        @NotBlank String htsId,

        @Schema(description = "증권 계좌번호 (형식: 계좌번호-상품코드)", example = "50012345-01")
        @NotBlank String accountNo,

        @Schema(description = "실전투자 계좌 여부 (false: 모의투자)", example = "true")
        @NotNull Boolean isRealAccount
) {}
