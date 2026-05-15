package com.modu.backend.domain.ai.exception;

import com.modu.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AiErrorCode implements ErrorCode {

    JUDGMENT_NOT_FOUND("AI_001", HttpStatus.NOT_FOUND, "AI 판단 기록을 찾을 수 없습니다."),
    INVALID_DECISION_MESSAGE("AI_002", HttpStatus.BAD_REQUEST, "AI 판단 메시지 필수 필드가 누락되었습니다."),
    UNSUPPORTED_FLOW_STATUS("AI_003", HttpStatus.BAD_REQUEST, "지원하지 않는 flow_status 값입니다."),
    INVALID_ORDER_PARAMS("AI_004", HttpStatus.BAD_REQUEST, "AI 주문 파라미터가 유효하지 않습니다.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;
}
