package com.modu.backend.domain.user.dto;

import com.modu.backend.global.validation.NullOrNotBlank;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;

@Schema(description = "한국투자증권 API 정보 수정 요청 (변경할 필드만 포함)")
public record KisKeyUpdateRequest(
        @Schema(description = "변경할 App Key (null이면 기존 값 유지)", example = "PSxxx...", nullable = true)
        @NullOrNotBlank
        String appKey,

        @Schema(description = "변경할 App Secret (null이면 기존 값 유지)", example = "yZxxx...", nullable = true)
        @NullOrNotBlank
        String appSecret,

        @Schema(description = "변경할 계좌번호 (형식: 계좌번호-상품코드, null이면 기존 값 유지)", example = "50012345-01", nullable = true)
        @NullOrNotBlank
        String accountNo,

        @Schema(description = "변경할 계좌 타입 (null이면 기존 값 유지)", example = "false", nullable = true)
        Boolean isRealAccount
) {
    /** 모든 필드가 null인 요청({}) 거부 */
    @AssertTrue(message = "변경할 필드를 최소 하나 이상 포함해야 합니다.")
    public boolean isAnyFieldProvided() {
        return appKey != null || appSecret != null || accountNo != null || isRealAccount != null;
    }
}
