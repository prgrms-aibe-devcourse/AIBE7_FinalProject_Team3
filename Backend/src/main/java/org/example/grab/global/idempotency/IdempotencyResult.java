package org.example.grab.global.idempotency;

import java.util.Objects;
import java.util.Optional;

public record IdempotencyResult<T>(Status status, T existingResult) {

    public IdempotencyResult {
        Objects.requireNonNull(status);
        if (status == Status.REPLAY) {
            Objects.requireNonNull(existingResult);
        }
        if (status == Status.NEW_REQUEST && existingResult != null) {
            throw new IllegalArgumentException("신규 요청에는 기존 결과가 없어야 합니다.");
        }
    }

    public static <T> IdempotencyResult<T> newRequest() {
        return new IdempotencyResult<>(Status.NEW_REQUEST, null);
    }

    public static <T> IdempotencyResult<T> replay(T existingResult) {
        return new IdempotencyResult<>(Status.REPLAY, existingResult);
    }

    public boolean isNewRequest() {
        return status == Status.NEW_REQUEST;
    }

    public Optional<T> getExistingResult() {
        return Optional.ofNullable(existingResult);
    }

    public enum Status {
        NEW_REQUEST,
        REPLAY
    }
}
