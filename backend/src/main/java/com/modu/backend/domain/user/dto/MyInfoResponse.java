package com.modu.backend.domain.user.dto;

import com.modu.backend.domain.user.entity.KisCredential;
import com.modu.backend.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "내 정보 조회 응답 (프로필 + KIS 연동 상태)")
public record MyInfoResponse(
        @Schema(description = "사용자 이름 (소셜 닉네임)", example = "홍길동")
        String name,

        @Schema(description = "이메일 (소셜 이메일 제공 미동의 시 null)", example = "user@example.com", nullable = true)
        String email,

        @Schema(description = "소셜 로그인 제공자", example = "kakao")
        String socialProvider,

        @Schema(description = "가입일시 (ISO-8601)", example = "2026-05-18T12:34:56+09:00")
        OffsetDateTime createdAt,

        @Schema(description = "한국투자증권 API 연동 상태")
        KisKeyStatus kisKeyStatus
) {
    @Schema(description = "KIS 연동 상태")
    public record KisKeyStatus(
            @Schema(description = "연동 여부", example = "true")
            boolean isConnected,

            @Schema(description = "연동된 계좌번호 (계좌번호-상품코드), 미연동 시 null", example = "50012345-01", nullable = true)
            String accountNo
    ) {
        public static KisKeyStatus notConnected() {
            return new KisKeyStatus(false, null);
        }

        public static KisKeyStatus from(KisCredential credential) {
            return new KisKeyStatus(true, credential.getAccountNo() + "-" + credential.getAccountPrdtCd());
        }
    }

    public static MyInfoResponse of(User user, KisCredential credential) {
        return new MyInfoResponse(
                user.getNickname(),
                user.getEmail(),
                user.getProvider(),
                user.getCreatedAt(),
                credential != null ? KisKeyStatus.from(credential) : KisKeyStatus.notConnected()
        );
    }
}
