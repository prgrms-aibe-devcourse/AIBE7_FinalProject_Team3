package org.example.grab.global.error;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.global.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    // 바인딩 실패의 기본 문구는 변환 예외 메시지라 거부된 입력값이 섞이므로 고정 문구로 바꾼다
    static final String BINDING_FAILURE_REASON = "값의 형식이 올바르지 않습니다.";

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
        // 거부된 값은 사용자가 보낸 원문이므로 기록하지 않고, 원인을 찾을 수 있도록 파라미터 이름과 기대 타입만 남긴다
        log.warn("요청 파라미터 타입 오류: {} ({})", e.getName(),
                e.getRequiredType() == null ? "unknown" : e.getRequiredType().getSimpleName());
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
                .map(error -> new ErrorResponse.FieldError(error.getField(),
                        error.isBindingFailure() ? BINDING_FAILURE_REASON : error.getDefaultMessage()))
                .toList();
        log.warn("요청 값 검증 실패: {} - {}", errorCode.getCode(), fieldErrors);
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode.getCode(), errorCode.getMessage(), fieldErrors));
    }

    /*
        다른 핸들러가 처리하지 않은 예외(Redis·DB 장애, 버그 등)는 500으로 응답한다.
        이 핸들러가 없으면 Boot 기본 처리가 /error로 error dispatch하는데, /error는 인증이 필요해 익명 요청은 401이 된다.
        재발급처럼 Access Token 없이 오는 요청에서 서버 장애가 401이 되면 클라이언트가 사용자를 로그아웃시킨다(GR-33 M03-03).
        상태 코드를 가진 Spring MVC 예외(404·405 등)와 Security 예외(401·403)는 같은 예외를 다시 던져 기존 처리에 맡긴다.
        원인을 찾아야 하므로 스택 트레이스는 서버 로그에 남기고, 응답에는 예외 메시지를 넣지 않는다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception e) throws Exception {
        if (e instanceof org.springframework.web.ErrorResponse
                || e instanceof AccessDeniedException
                || e instanceof AuthenticationException) {
            throw e;
        }

        log.error("처리하지 못한 예외", e);
        CommonErrorCode errorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode.getCode(), errorCode.getMessage(), List.of()));
    }
}
