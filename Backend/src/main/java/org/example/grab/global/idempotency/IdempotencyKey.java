package org.example.grab.global.idempotency;

import java.util.Locale;
import java.util.UUID;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;

public record IdempotencyKey(String value) {

    private static final int UUID_LENGTH = 36;

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_IDEMPOTENCY_KEY);
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() != UUID_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_IDEMPOTENCY_KEY);
        }

        try {
            UUID uuid = UUID.fromString(normalized);
            if (!uuid.toString().equals(normalized)) {
                throw new BusinessException(CommonErrorCode.INVALID_IDEMPOTENCY_KEY);
            }
            value = uuid.toString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }

    public static IdempotencyKey from(String value) {
        return new IdempotencyKey(value);
    }

    @Override
    public String toString() {
        return value.substring(0, 8) + "-****-****-****-************";
    }
}
