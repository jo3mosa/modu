package com.modu.backend.domain.user.dto;

/**
 * KIS API 정보 수정 요청 DTO (PATCH)
 *
 * 모든 필드 선택 - null인 필드는 기존 값 유지
 * accountNo 형식: "계좌번호-상품코드" (예: 50012345-01)
 */
public record KisKeyUpdateRequest(
        String appKey,
        String appSecret,
        String accountNo,
        Boolean isRealAccount
) {}
