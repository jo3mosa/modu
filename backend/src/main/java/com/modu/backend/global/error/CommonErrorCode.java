package com.modu.backend.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    // --- [공통 입력값 오류 4xx] ---
    VALIDATION_ERROR("CMM_001", HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    TYPE_MISMATCH("CMM_002", HttpStatus.BAD_REQUEST, "요청 파라미터의 형식이 잘못되었습니다."),
    MALFORMED_JSON("CMM_003", HttpStatus.BAD_REQUEST, "요청 본문의 JSON 형식이 올바르지 않습니다."),
    MISSING_PARAMETER("CMM_004", HttpStatus.BAD_REQUEST, "필수 요청 파라미터가 누락되었습니다."),

    // --- [시스템 오류 5xx] ---
    CONCURRENT_CONFLICT("SYS_001", HttpStatus.CONFLICT, "동시 요청 충돌이 발생했습니다. 잠시 후 다시 시도해 주세요."),
    EXTERNAL_API_ERROR("SYS_002", HttpStatus.BAD_GATEWAY, "외부 API 호출 중 오류가 발생했습니다."),
    INTERNAL_SERVER_ERROR("SYS_003", HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;
}
