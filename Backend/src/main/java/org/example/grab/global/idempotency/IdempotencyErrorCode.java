package org.example.grab.global.idempotency;

public enum IdempotencyErrorCode {
    INVALID_REQUEST(400, "유효한 Idempotency-Key 헤더가 필요합니다."),
    DUPLICATE_IDEMPOTENCY_KEY(409, "동일한 Idempotency-Key에 다른 요청 본문을 사용할 수 없습니다.");

    private final int httpStatus;
    private final String message;

    IdempotencyErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
