package com.modu.backend.domain.trading.exception;

import com.modu.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 주문 도메인 에러 코드
 */
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {

    INSUFFICIENT_BALANCE("ORDER_001", HttpStatus.BAD_REQUEST, "잔고가 부족합니다."),
    DAILY_ORDER_LIMIT_EXCEEDED("ORDER_003", HttpStatus.BAD_REQUEST, "일일 최대 주문 금액을 초과했습니다."),
    ORDER_ALREADY_FILLED("ORDER_004", HttpStatus.BAD_REQUEST, "이미 체결된 주문은 정정/취소할 수 없습니다."),
    ORDER_NOT_FOUND("ORDER_005", HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    MARKET_CLOSED("MARKET_001", HttpStatus.BAD_REQUEST, "장이 마감된 상태입니다.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;
}
