package com.modu.backend.domain.market.exception;

import com.modu.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MarketErrorCode implements ErrorCode {

    STOCK_NOT_FOUND("MKT_001", HttpStatus.NOT_FOUND, "존재하지 않는 종목코드입니다."),

    KIS_PRICE_FETCH_FAILED("MKT_002", HttpStatus.BAD_GATEWAY, "종목 시세 조회에 실패했습니다."),

    INVALID_CANDLE_PERIOD("MKT_003", HttpStatus.BAD_REQUEST, "유효하지 않은 기간 타입입니다. (D/W/M/1/5/60)"),

    INVALID_CANDLE_DATE_RANGE("MKT_004", HttpStatus.BAD_REQUEST, "유효하지 않은 캔들 조회 날짜 범위입니다."),

    MINUTE_CANDLE_MULTI_DAY_NOT_SUPPORTED("MKT_005", HttpStatus.BAD_REQUEST,
            "분봉은 하루치 데이터만 조회할 수 있습니다. startDate와 endDate를 동일하게 입력하세요.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;
}
