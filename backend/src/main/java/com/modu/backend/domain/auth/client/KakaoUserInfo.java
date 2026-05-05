package com.modu.backend.domain.auth.client;

/**
 * 카카오 API에서 가져온 사용자 정보를 AuthService로 전달하는 DTO
 *
 * 카카오 응답의 중첩 구조를 평탄화해서 필요한 필드만 담는다.
 * (원본 카카오 응답 파싱은 KakaoOAuthClient 내부에서 처리)
 */
public record KakaoUserInfo(
        String providerId,  // 카카오 고유 사용자 ID (숫자를 문자열로 변환)
        String nickname,
        String email        // 이메일 제공 미동의 시 null
) {}
