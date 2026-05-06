package com.modu.backend.domain.market.exception;

import com.modu.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 시장/종목 도메인 에러 코드
 *
 * 코드 체계: MKT_001 ~
 */
@Getter
@RequiredArgsConstructor
public enum MarketErrorCode implements ErrorCode {

    /** 존재하지 않는 종목코드로 조회 시 */
    STOCK_NOT_FOUND("MKT_001", HttpStatus.NOT_FOUND, "존재하지 않는 종목코드입니다."),

    /** KIS 시세 조회 API 호출 실패 */
    KIS_PRICE_FETCH_FAILED("MKT_002", HttpStatus.BAD_GATEWAY, "종목 시세 조회에 실패했습니다.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;
}
