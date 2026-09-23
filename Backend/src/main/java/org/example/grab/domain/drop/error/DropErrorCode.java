package org.example.grab.domain.drop.error;

import org.example.grab.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum DropErrorCode implements ErrorCode {

    DROP_NOT_FOUND(HttpStatus.NOT_FOUND, "DROP을 찾을 수 없습니다."),
    DROP_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 DROP에 대한 권한이 없습니다."),
    DROP_NOT_EDITABLE(HttpStatus.CONFLICT, "수정할 수 없는 상태의 DROP입니다."),
    INVALID_SCHEDULE(HttpStatus.UNPROCESSABLE_CONTENT, "판매 일정이 올바르지 않습니다."),
    INVALID_OPTION_COMBINATION(HttpStatus.UNPROCESSABLE_CONTENT, "옵션 그룹·값·SKU 조합이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    DropErrorCode(HttpStatus status, String message) {
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
