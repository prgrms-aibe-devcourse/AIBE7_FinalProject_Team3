package org.example.grab.global.idempotency;

import java.util.Locale;
import java.util.regex.Pattern;

public record RequestHash(String value) {

    private static final Pattern SHA_256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    public RequestHash {
        if (value == null) {
            throw new IllegalArgumentException("요청 해시는 필수입니다.");
        }

        value = value.toLowerCase(Locale.ROOT);
        if (!SHA_256_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("요청 해시는 SHA-256 형식이어야 합니다.");
        }
    }

    public static RequestHash from(String value) {
        return new RequestHash(value);
    }
}
