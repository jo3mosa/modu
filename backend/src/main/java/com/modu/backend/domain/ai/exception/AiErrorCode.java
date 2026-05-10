package com.modu.backend.domain.ai.exception;

import com.modu.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AiErrorCode implements ErrorCode {

    JUDGMENT_NOT_FOUND("AI_001", HttpStatus.NOT_FOUND, "AI 판단 기록을 찾을 수 없습니다.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;
}
