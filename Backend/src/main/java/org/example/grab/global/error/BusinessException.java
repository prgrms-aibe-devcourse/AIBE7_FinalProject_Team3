package org.example.grab.global.error;

import org.example.grab.global.common.ErrorResponse;

import java.util.List;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ErrorResponse.FieldError> fieldErrors;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
    }

    /** 위반 항목을 응답의 fieldErrors로 알려야 하는 도메인 검증 실패에 쓴다. */
    public BusinessException(ErrorCode errorCode, List<ErrorResponse.FieldError> fieldErrors) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<ErrorResponse.FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
