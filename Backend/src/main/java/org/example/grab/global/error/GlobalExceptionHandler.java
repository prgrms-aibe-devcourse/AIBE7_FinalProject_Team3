package org.example.grab.global.error;

import lombok.extern.slf4j.Slf4j;
import org.example.grab.global.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("비즈니스 예외: {} - {}", errorCode.getCode(), e.getMessage());
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode.getCode(), e.getMessage(), e.getFieldErrors()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("요청 파라미터 타입 오류: {} = {}", e.getName(), e.getValue());
        return ResponseEntity.status(CommonErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(
                        CommonErrorCode.INVALID_REQUEST.getCode(),
                        CommonErrorCode.INVALID_REQUEST.getMessage(),
                        List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        log.warn("요청 값 검증 실패: {}", fieldErrors);
        return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.getStatus())
                .body(ErrorResponse.of(
                        CommonErrorCode.VALIDATION_FAILED.getCode(),
                        CommonErrorCode.VALIDATION_FAILED.getMessage(),
                        fieldErrors));
    }
}
