package org.example.grab.global.common;

import java.util.List;

// 공통 에러 응답
public record ErrorResponse(
        boolean success,
        Object data,
        ErrorDetail error
) {

    /*
        오류 응답을 명세에 맞게 생성하기 위한 구조
     */
    public static ErrorResponse of(String code, String message, List<FieldError> fieldErrors) {
        return new ErrorResponse(false, null, new ErrorDetail(code, message, fieldErrors));
    }

    /*
        오류 전체 정보를 표현하는 중첩 record
     */
    public record ErrorDetail(
            String code,
            String message,
            List<FieldError> fieldErrors
    ) {

        /*
            1.5 명세가 필드 오류가 없으면 빈 배열로 정의하므로 null을 빈 목록으로 바꾼다.
            오류 응답 생성 중 예외가 나면 500으로 바뀌므로 거부하지 않는다.
         */
        public ErrorDetail(String code, String message, List<FieldError> fieldErrors) {
            this.code = code;
            this.message = message;
            this.fieldErrors = fieldErrors == null ? List.of() : fieldErrors;
        }
    }

    /*
        개별 필드 오류 하나를 표기
     */
    public record FieldError(
            String field,
            String reason
    ) {
    }
}
