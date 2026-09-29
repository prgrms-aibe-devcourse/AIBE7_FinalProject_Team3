package org.example.grab.domain.payment.error;

import org.example.grab.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum PaymentErrorCode implements ErrorCode {

    PAYMENT_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리됐거나 진행 중인 결제가 있습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "결제 금액이 주문 금액과 일치하지 않습니다."),
    PAYMENT_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT, "결제 유효시간이 지났습니다.");

    private final HttpStatus status;
    private final String message;

    PaymentErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public HttpStatus getStatus() {
        return status;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getMessage() {
        return message;
    }
}
