package org.example.grab.domain.auth.support;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// GR-33 U4: 새 세션 TTL은 14일, 절대 만료가 가까우면 남은 시간, 지났으면 거부
class RefreshTokenTtlCalculatorTest {

    private static final Duration IDLE_TTL = Duration.ofDays(14);
    private static final Duration ABSOLUTE_TTL = Duration.ofDays(30);
    private static final Instant NOW = Instant.parse("2026-10-02T06:00:00Z");

    private final RefreshTokenTtlCalculator calculator = new RefreshTokenTtlCalculator(
            new RefreshTokenProperties(IDLE_TTL, ABSOLUTE_TTL), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("새 세션의 절대 만료는 지금부터 30일 뒤이고 TTL은 14일이다")
    void newSessionGetsIdleTtl() {
        // when
        Instant sessionExpiresAt = calculator.newSessionExpiresAt();
        Duration ttl = calculator.calculateTtl(sessionExpiresAt);

        // then
        assertThat(sessionExpiresAt).isEqualTo(NOW.plus(ABSOLUTE_TTL));
        assertThat(ttl).isEqualTo(IDLE_TTL);
    }

    @Test
    @DisplayName("절대 만료까지 14일보다 적게 남으면 남은 시간이 TTL이다")
    void remainingTimeBecomesTtlNearAbsoluteExpiry() {
        // given: notes 4절 표의 20일 차 재발급
        Instant sessionExpiresAt = NOW.plus(Duration.ofDays(11));

        // when
        Duration ttl = calculator.calculateTtl(sessionExpiresAt);

        // then
        assertThat(ttl).isEqualTo(Duration.ofDays(11));
    }

    @Test
    @DisplayName("절대 만료까지 정확히 14일 남으면 TTL은 14일이다")
    void idleTtlWhenRemainingEqualsIdleTtl() {
        // when
        Duration ttl = calculator.calculateTtl(NOW.plus(IDLE_TTL));

        // then
        assertThat(ttl).isEqualTo(IDLE_TTL);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -86_400_000})
    @DisplayName("절대 만료 시각이 지금이거나 지났으면 INVALID_TOKEN 예외가 발생한다")
    void rejectsExpiredSession(long offsetMillis) {
        // given
        Instant sessionExpiresAt = NOW.plusMillis(offsetMillis);

        // when & then
        assertThatThrownBy(() -> calculator.calculateTtl(sessionExpiresAt))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_TOKEN));
    }

    @Test
    @DisplayName("절대 만료 시각이 없으면 예외가 발생한다")
    void rejectsMissingSessionExpiresAt() {
        // when & then
        assertThatThrownBy(() -> calculator.calculateTtl(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
