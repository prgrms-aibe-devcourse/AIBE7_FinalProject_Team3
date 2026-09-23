package org.example.grab.global.idempotency;

import java.util.Locale;
import java.util.UUID;

public record IdempotencyKey(String value) {

    private static final int UUID_LENGTH = 36;

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw IdempotencyException.invalidKey();
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() != UUID_LENGTH) {
            throw IdempotencyException.invalidKey();
        }

        try {
            UUID uuid = UUID.fromString(normalized);
            if (!uuid.toString().equals(normalized)) {
                throw IdempotencyException.invalidKey();
            }
            value = uuid.toString();
        } catch (IllegalArgumentException exception) {
            throw IdempotencyException.invalidKey();
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
