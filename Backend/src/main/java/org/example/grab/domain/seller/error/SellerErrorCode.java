package org.example.grab.domain.seller.error;

import org.example.grab.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum SellerErrorCode implements ErrorCode {

    SELLER_APPLICATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 심사 중이거나 승인된 판매자 신청이 있습니다."),
    DUPLICATE_BUSINESS_REGISTRATION_NUMBER(HttpStatus.CONFLICT, "이미 다른 계정이 신청한 사업자등록번호입니다."),
    BUSINESS_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "계속사업자가 아닌 사업자등록번호입니다."),
    SELLER_APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "판매자 신청을 찾을 수 없습니다."),
    INVALID_BUSINESS_REGISTRATION_NUMBER(HttpStatus.BAD_REQUEST, "사업자등록번호는 하이픈 없는 10자리 숫자여야 합니다.");

    private final HttpStatus status;
    private final String message;

    SellerErrorCode(HttpStatus status, String message) {
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
