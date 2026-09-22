package org.example.grab.global.idempotency;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.ErrorCode;

import java.util.UUID;

public record IdempotencyKey(String value) {

    public static IdempotencyKey from(String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return new IdempotencyKey(value);
    }
}
