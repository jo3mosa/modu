package com.modu.backend.domain.investment.exception;

import com.modu.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum InvestmentErrorCode implements ErrorCode {

    PROFILE_NOT_FOUND("INVEST_001", HttpStatus.NOT_FOUND, "투자 성향 프로필을 찾을 수 없습니다."),
    PROFILE_ALREADY_EXISTS("INVEST_002", HttpStatus.CONFLICT, "투자 성향 프로필이 이미 존재합니다."),
    REQUIRED_ANSWER_MISSING("INVEST_003", HttpStatus.BAD_REQUEST, "필수 답변이 누락되었습니다."),
    INVALID_PROFILE_ANSWER("INVEST_004", HttpStatus.BAD_REQUEST, "유효하지 않은 설문 답변입니다."),
    DUPLICATE_PROFILE_ANSWER("INVEST_005", HttpStatus.BAD_REQUEST, "중복된 설문 답변입니다.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;
}
