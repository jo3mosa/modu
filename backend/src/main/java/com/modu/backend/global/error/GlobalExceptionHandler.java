package com.modu.backend.global.error;

import com.modu.backend.global.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TRACE_ID_KEY = "traceId";

    // @Valid 유효성 검사 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String traceId = MDC.get(TRACE_ID_KEY);
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse(CommonErrorCode.VALIDATION_ERROR.getDefaultMessage());

        log.warn("[ValidationError] TraceId: {}, Message: {}", traceId, message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(message, CommonErrorCode.VALIDATION_ERROR, traceId));
    }

    // 잘못된 JSON 형식
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadableException(HttpMessageNotReadableException e) {
        String traceId = MDC.get(TRACE_ID_KEY);
        log.warn("[MalformedJson] TraceId: {}, Message: {}", traceId, e.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(CommonErrorCode.MALFORMED_JSON.getDefaultMessage(), CommonErrorCode.MALFORMED_JSON, traceId));
    }

    // 필수 @RequestParam 누락
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameterException(MissingServletRequestParameterException e) {
        String traceId = MDC.get(TRACE_ID_KEY);
        String message = String.format("필수 파라미터 '%s'가 누락되었습니다.", e.getParameterName());
        log.warn("[MissingParameter] TraceId: {}, Message: {}", traceId, message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(message, CommonErrorCode.MISSING_PARAMETER, traceId));
    }

    // 파라미터 타입 불일치
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatchException(MethodArgumentTypeMismatchException e) {
        String traceId = MDC.get(TRACE_ID_KEY);
        String message = String.format("파라미터 '%s'의 형식이 잘못되었습니다.", e.getName());
        log.warn("[TypeMismatchError] TraceId: {}, Message: {}", traceId, message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(message, CommonErrorCode.TYPE_MISMATCH, traceId));
    }

    // 비즈니스 예외
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException e) {
        ErrorCode errorCode = e.getErrorCode();
        String traceId = MDC.get(TRACE_ID_KEY);
        log.warn("[ApiException] TraceId: {}, Code: {}, Message: {}", traceId, errorCode.getCode(), e.getMessage());

        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode.getDefaultMessage(), errorCode, traceId));
    }

    // 낙관적 락 충돌 (주문 동시성 제어)
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLockingFailureException(ObjectOptimisticLockingFailureException e) {
        String traceId = MDC.get(TRACE_ID_KEY);
        log.warn("[OptimisticLockingFailure] TraceId: {}", traceId, e);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(CommonErrorCode.CONCURRENT_CONFLICT.getDefaultMessage(), CommonErrorCode.CONCURRENT_CONFLICT, traceId));
    }

    // KIS 외부 API 호출 오류 - 내부 응답 바디는 로그에만 기록, 클라이언트에 노출 금지
    @ExceptionHandler(org.springframework.web.client.RestClientResponseException.class)
    public ResponseEntity<ApiResponse<Void>> handleRestClientException(org.springframework.web.client.RestClientResponseException e) {
        String traceId = MDC.get(TRACE_ID_KEY);
        log.error("[External API Error] TraceId: {}, Status: {}, Body: {}", traceId, e.getStatusCode(), e.getResponseBodyAsString(), e);

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(CommonErrorCode.EXTERNAL_API_ERROR.getDefaultMessage(), CommonErrorCode.EXTERNAL_API_ERROR, traceId));
    }

    // 미처리 예외 - 내부 상세 메시지 클라이언트 노출 금지
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        String traceId = MDC.get(TRACE_ID_KEY);
        log.error("[Unexpected Error] TraceId: {}", traceId, e);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(CommonErrorCode.INTERNAL_SERVER_ERROR.getDefaultMessage(), CommonErrorCode.INTERNAL_SERVER_ERROR, traceId));
    }
}
