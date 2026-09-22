package org.example.grab.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값 검증에 실패했습니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    DROP_NOT_ON_SALE(HttpStatus.CONFLICT, "판매 중인 DROP이 아닙니다."),
    SALE_NOT_STARTED(HttpStatus.CONFLICT, "판매가 시작되지 않았습니다."),
    SALE_ENDED(HttpStatus.CONFLICT, "판매가 종료되었습니다."),
    OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "주문 옵션을 찾을 수 없습니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 주문에 접근할 수 없습니다."),
    ORDER_NOT_CANCELABLE(HttpStatus.CONFLICT, "취소할 수 없는 주문입니다."),
    ORDER_STATUS_CONFLICT(HttpStatus.CONFLICT, "주문 상태가 이미 변경되었습니다."),
    PAYMENT_CANCEL_FAILED(HttpStatus.CONFLICT, "결제 취소에 실패했습니다."),
    PAYMENT_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 결제입니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "결제 금액이 일치하지 않습니다."),
    PAYMENT_EXPIRED(HttpStatus.UNPROCESSABLE_ENTITY, "결제 유효시간이 만료되었습니다."),
    DUPLICATE_IDEMPOTENCY_KEY(HttpStatus.CONFLICT, "동일한 멱등 키가 다른 요청에 사용되었습니다."),
    INSUFFICIENT_STOCK(HttpStatus.UNPROCESSABLE_ENTITY, "재고가 부족합니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
