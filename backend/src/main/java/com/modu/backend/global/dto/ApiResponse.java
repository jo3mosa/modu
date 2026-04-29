package com.modu.backend.global.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.modu.backend.global.error.ErrorCode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String message,
        String errorCode,
        String traceId,
        T data
) {
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, null, null, data);
    }

    public static <T> ApiResponse<T> success(String message) {
        return new ApiResponse<>(true, message, null, null, null);
    }

    public static <T> ApiResponse<T> fail(String message, ErrorCode errorCode, String traceId) {
        return new ApiResponse<>(
                false,
                message,
                errorCode != null ? errorCode.getCode() : "SYS_ERR",
                traceId,
                null
        );
    }
}
