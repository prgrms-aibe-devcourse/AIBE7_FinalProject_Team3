package org.example.grab.global.error;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.global.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ValidationErrorCodeResolver validationErrorCodeResolver;

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

    // 에러의 BindingResult를 resolve 통해 ValidationFailure 생성 후
    // failure에서 errorCode와 FieldError 추출
    /*
        요청 본문이 없거나 JSON으로 해석할 수 없는 경우(MEMBER_AUTH.md 1.2.3 "요청 본문 오류").
        파싱 예외 메시지에는 요청 본문의 일부나 입력값이 섞일 수 있으므로 응답과 로그에 넣지 않고 원인 예외의 타입만 남긴다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("요청 본문 해석 실패: {}", e.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.status(CommonErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(
                        CommonErrorCode.INVALID_REQUEST.getCode(),
                        CommonErrorCode.INVALID_REQUEST.getMessage(),
                        List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        ValidationFailure failure = validationErrorCodeResolver.resolve(e.getBindingResult());
        ErrorCode errorCode = failure.errorCode();
        List<ErrorResponse.FieldError> fieldErrors = failure.fieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        log.warn("요청 값 검증 실패: {} - {}", errorCode.getCode(), fieldErrors);
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode.getCode(), errorCode.getMessage(), fieldErrors));
    }
}
