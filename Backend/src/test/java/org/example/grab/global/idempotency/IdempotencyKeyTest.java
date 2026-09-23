package org.example.grab.global.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    @Test
    @DisplayName("UUID 형식 멱등 키를 소문자 표준 형식으로 정규화한다")
    void normalizesUuidKey() {
        // given
        String rawKey = "550E8400-E29B-41D4-A716-446655440000";

        // when
        IdempotencyKey key = IdempotencyKey.from(rawKey);

        // then
        assertThat(key.value()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(key.toString()).doesNotContain("e29b-41d4-a716-446655440000");
    }

    @Test
    @DisplayName("누락되거나 UUID 형식이 아닌 멱등 키를 거부한다")
    void rejectsInvalidKey() {
        assertInvalidKey(null);
        assertInvalidKey(" ");
        assertInvalidKey("not-a-uuid");
        assertInvalidKey("1-1-1-1-1");
    }

    private void assertInvalidKey(String rawKey) {
        assertThatThrownBy(() -> IdempotencyKey.from(rawKey))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(CommonErrorCode.INVALID_IDEMPOTENCY_KEY));
    }
}
