package org.example.grab.global.error;

import org.example.grab.global.common.ErrorResponse;

import java.util.List;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ErrorResponse.FieldError> fieldErrors;
    private final Long retryAfterSeconds;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
        this.retryAfterSeconds = null;
    }

    /** 위반 항목을 응답의 fieldErrors로 알려야 하는 도메인 검증 실패에 쓴다. */
    public BusinessException(ErrorCode errorCode, List<ErrorResponse.FieldError> fieldErrors) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.retryAfterSeconds = null;
    }

    public BusinessException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode.getMessage());
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("재시도 대기 시간은 양수 초여야 합니다.");
        }
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<ErrorResponse.FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
