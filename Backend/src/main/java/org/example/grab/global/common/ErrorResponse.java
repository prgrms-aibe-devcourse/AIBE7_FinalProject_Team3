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
