package org.example.grab.global.idempotency;

import lombok.Getter;

@Getter
public class IdempotencyException extends RuntimeException {

    private final IdempotencyErrorCode errorCode;

    private IdempotencyException(IdempotencyErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public static IdempotencyException invalidKey() {
        return new IdempotencyException(IdempotencyErrorCode.INVALID_REQUEST);
    }

    public static IdempotencyException duplicateKey() {
        return new IdempotencyException(IdempotencyErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
    }
}
