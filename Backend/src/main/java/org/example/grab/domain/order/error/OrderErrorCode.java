package org.example.grab.domain.order.error;

import org.example.grab.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum OrderErrorCode implements ErrorCode {

    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 주문을 조회할 권한이 없습니다."),
    DROP_NOT_ON_SALE(HttpStatus.CONFLICT, "판매 중인 DROP이 아닙니다."),
    SALE_NOT_STARTED(HttpStatus.CONFLICT, "판매가 아직 시작되지 않았습니다."),
    SALE_ENDED(HttpStatus.CONFLICT, "판매가 종료되었습니다."),
    OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "주문 옵션을 찾을 수 없습니다."),
    INSUFFICIENT_STOCK(HttpStatus.UNPROCESSABLE_CONTENT, "옵션 재고가 부족합니다.");

    private final HttpStatus status;
    private final String message;

    OrderErrorCode(HttpStatus status, String message) {
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
