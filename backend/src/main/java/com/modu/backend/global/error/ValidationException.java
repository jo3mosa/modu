package com.modu.backend.global.error;

import lombok.Getter;

@Getter
public class ValidationException extends RuntimeException {

    private final ErrorCode errorCode;

    public ValidationException(String message) {
        super(message);
        this.errorCode = CommonErrorCode.VALIDATION_ERROR;
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = CommonErrorCode.VALIDATION_ERROR;
    }
}
