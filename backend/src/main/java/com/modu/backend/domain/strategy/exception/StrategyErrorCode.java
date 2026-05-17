package com.modu.backend.domain.strategy.exception;

import com.modu.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StrategyErrorCode implements ErrorCode {

    KILL_SWITCH_ACTIVATED("STRATEGY_002", HttpStatus.SERVICE_UNAVAILABLE, "Kill-switch가 발동된 상태입니다. 해제 후 다시 시도해주세요."),
    RULE_NOT_FOUND("STRATEGY_005", HttpStatus.NOT_FOUND, "리스크 룰셋 정보가 없습니다.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;
}
