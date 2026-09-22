package org.example.grab.global.error;

import java.util.List;

public record ErrorResponse(
        boolean success,
        Object data,
        ErrorDetail error
) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(
                false,
                null,
                new ErrorDetail(errorCode.name(), errorCode.getMessage(), List.of())
        );
    }

    public static ErrorResponse validation(List<FieldError> fieldErrors) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return new ErrorResponse(
                false,
                null,
                new ErrorDetail(code.name(), code.getMessage(), fieldErrors)
        );
    }

    public record ErrorDetail(String code, String message, List<FieldError> fieldErrors) {
    }

    public record FieldError(String field, String reason) {
    }
}
