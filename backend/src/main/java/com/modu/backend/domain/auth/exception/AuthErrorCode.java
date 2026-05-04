package com.modu.backend.domain.auth.exception;

import com.modu.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 인증/인가 도메인 에러 코드
 *
 * 코드 체계: AUTH_001 ~ AUTH_007
 * 소셜 로그인, JWT 토큰 관리, 사용자 조회 관련 예외를 정의한다.
 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    // ── 소셜 로그인 ────────────────────────────────────────────────────────────

    /** Path Variable provider 값이 지원 목록(kakao 등)에 없을 때 */
    UNSUPPORTED_PROVIDER("AUTH_001", HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 로그인 제공자입니다."),

    /** 카카오로부터 액세스 토큰 발급 요청이 실패했을 때 (네트워크 오류, 잘못된 code 등) */
    KAKAO_TOKEN_FETCH_FAILED("AUTH_002", HttpStatus.BAD_GATEWAY, "카카오 액세스 토큰 발급에 실패했습니다."),

    /** 카카오 사용자 정보 조회 API 호출이 실패했을 때 */
    KAKAO_USER_INFO_FAILED("AUTH_003", HttpStatus.BAD_GATEWAY, "카카오 사용자 정보 조회에 실패했습니다."),

    // ── JWT 토큰 ────────────────────────────────────────────────────────────────

    /** 서명 불일치, 잘못된 형식 등 토큰 자체가 유효하지 않을 때 */
    INVALID_TOKEN("AUTH_004", HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),

    /** 토큰 서명은 유효하나 만료 시각이 지났을 때 */
    EXPIRED_TOKEN("AUTH_005", HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),

    /** 쿠키에 Refresh Token이 없거나 DB에서 찾을 수 없을 때 */
    REFRESH_TOKEN_NOT_FOUND("AUTH_006", HttpStatus.UNAUTHORIZED, "Refresh Token이 존재하지 않습니다."),

    // ── 사용자 ──────────────────────────────────────────────────────────────────

    /** userId로 사용자를 조회했으나 존재하지 않을 때 (테스트 로그인 등) */
    USER_NOT_FOUND("AUTH_007", HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;
}
