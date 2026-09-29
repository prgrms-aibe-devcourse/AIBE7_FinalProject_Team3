package org.example.grab.global.error;

import org.springframework.validation.FieldError;

import java.util.List;

/*
    요청 검증 실패를 응답으로 옮기기 전에 정리한 결과다.
    errorCode는 응답의 최상위 error.code, fieldErrors는 응답 fieldErrors에 담을 순서대로 정렬한 필드 오류다.
 */
public record ValidationFailure(
        ErrorCode errorCode,
        List<FieldError> fieldErrors
) {
}
