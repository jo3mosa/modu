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
    SELL_REQUIRES_STOCK_CODE("ORDER_002", HttpStatus.BAD_REQUEST, "매도 조회 시 종목코드가 필요합니다."),
    DAILY_ORDER_LIMIT_EXCEEDED("ORDER_003", HttpStatus.BAD_REQUEST, "일일 최대 주문 금액을 초과했습니다."),
    ORDER_ALREADY_FILLED("ORDER_004", HttpStatus.BAD_REQUEST, "이미 체결된 주문은 정정/취소할 수 없습니다."),
    ORDER_NOT_FOUND("ORDER_005", HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    /** 본인 주문이 아닌 경우 — API 명세 CMM_002 와 별개로 내부 코드 ORDER_006 사용 */
    ORDER_FORBIDDEN("ORDER_006", HttpStatus.FORBIDDEN, "본인의 주문만 정정/취소할 수 있습니다."),
    MARKET_CLOSED("MARKET_001", HttpStatus.BAD_REQUEST, "장이 마감된 상태입니다."),

    /** 거래 이력 조회 — 기간이 1년을 초과 */
    HISTORY_PERIOD_TOO_LONG("ORDER_007", HttpStatus.BAD_REQUEST, "조회 기간은 최대 1년까지 가능합니다."),
    /** 거래 이력 조회 — from > to */
    HISTORY_INVALID_DATE_RANGE("ORDER_008", HttpStatus.BAD_REQUEST, "시작일이 종료일보다 늦을 수 없습니다.");
    // KIS_TOKEN_INVALIDATED 는 KIS 인증 공통 영역이라 UserErrorCode 로 이동됨 (USER_007)

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;
}
