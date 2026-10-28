package org.example.grab.domain.drop.error;

import org.example.grab.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum DropErrorCode implements ErrorCode {

    STOCK_STREAM_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "재고 구독 연결 또는 요청 제한을 초과했습니다."),
    STOCK_STREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "재고 구독을 일시적으로 사용할 수 없습니다."),
    DROP_NOT_FOUND(HttpStatus.NOT_FOUND, "DROP을 찾을 수 없습니다."),
    DROP_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 DROP에 대한 권한이 없습니다."),
    DROP_NOT_EDITABLE(HttpStatus.CONFLICT, "수정할 수 없는 상태의 DROP입니다."),
    DROP_NOT_WISHABLE(HttpStatus.CONFLICT, "WISH할 수 없는 상태의 DROP입니다."),
    GRAB_ALREADY_STARTED(HttpStatus.CONFLICT, "이미 GRAB이 시작된 DROP입니다."),
    INVALID_SCHEDULE(HttpStatus.UNPROCESSABLE_CONTENT, "판매 일정이 올바르지 않습니다."),
    INVALID_OPTION_COMBINATION(HttpStatus.UNPROCESSABLE_CONTENT, "옵션 그룹·값·SKU 조합이 올바르지 않습니다."),
    DUPLICATE_OPTION_COMBINATION(HttpStatus.CONFLICT, "동일한 옵션값 조합의 SKU가 중복됩니다."),
    AI_DRAFT_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "오늘 사진 초안 작성 횟수를 모두 사용했습니다."),
    AI_DRAFT_FAILED(HttpStatus.BAD_GATEWAY, "사진으로 초안을 만들지 못했습니다."),
    AI_DRAFT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "사진 초안 작성을 사용할 수 없습니다.");

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
