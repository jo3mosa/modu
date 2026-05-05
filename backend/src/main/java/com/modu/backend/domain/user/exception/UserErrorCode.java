package com.modu.backend.domain.user.exception;

import com.modu.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 사용자 도메인 에러 코드
 *
 * 코드 체계: USER_001 ~ USER_003
 * 한국투자증권 API 연동 관련 예외 정의
 */
@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    /** 이미 KIS API가 연동된 사용자가 POST 재요청 시 */
    KIS_ALREADY_CONNECTED("USER_001", HttpStatus.CONFLICT, "이미 한국투자증권 API가 연동되어 있습니다."),

    /** PATCH/DELETE 요청 시 연동 정보가 없을 때 */
    KIS_NOT_CONNECTED("USER_002", HttpStatus.NOT_FOUND, "연동된 한국투자증권 API 정보가 없습니다."),

    /** accountNo가 "계좌번호-상품코드" 형식이 아닐 때 */
    INVALID_ACCOUNT_NO_FORMAT("USER_003", HttpStatus.BAD_REQUEST, "계좌번호 형식이 올바르지 않습니다. (예: 50012345-01)"),

    /** KIS 연동 시 appKey/appSecret으로 토큰 발급 실패 (잘못된 자격증명 또는 KIS 서버 오류) */
    KIS_TOKEN_ISSUANCE_FAILED("USER_004", HttpStatus.BAD_GATEWAY, "한국투자증권 API 토큰 발급에 실패했습니다. 앱키와 시크릿을 확인해주세요."),

    /** TTTC8434R 등 실전투자 전용 API를 모의계좌로 호출 시 */
    KIS_MOCK_ACCOUNT_NOT_SUPPORTED("USER_005", HttpStatus.BAD_REQUEST, "모의투자 계좌는 지원하지 않습니다. 실전투자 계좌로 연동해주세요."),

    /** KIS 자격증명 복호화 실패 (암호화 키 변경 또는 데이터 손상) */
    KIS_CREDENTIAL_DECRYPT_FAILED("USER_006", HttpStatus.INTERNAL_SERVER_ERROR, "한국투자증권 API 자격증명 복호화에 실패했습니다.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;
}
