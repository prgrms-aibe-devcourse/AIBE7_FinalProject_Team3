package org.example.grab.domain.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationSendLimitPropertiesTest {

    private static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    private static final Duration EMAIL_WINDOW = Duration.ofHours(24);
    private static final Duration IP_WINDOW = Duration.ofHours(1);

    @Test
    @DisplayName("모든 값이 0보다 크면 그대로 담는다")
    void acceptsPositiveValues() {
        // when
        EmailVerificationSendLimitProperties properties =
                new EmailVerificationSendLimitProperties(RESEND_INTERVAL, 10, EMAIL_WINDOW, 30, IP_WINDOW);

        // then
        assertThat(properties.resendInterval()).isEqualTo(RESEND_INTERVAL);
        assertThat(properties.emailMaxRequests()).isEqualTo(10);
        assertThat(properties.emailWindow()).isEqualTo(EMAIL_WINDOW);
        assertThat(properties.ipMaxRequests()).isEqualTo(30);
        assertThat(properties.ipWindow()).isEqualTo(IP_WINDOW);
    }

    @Test
    @DisplayName("시간 값이 없거나 0 이하면 해당 설정 이름을 알리고 거부한다")
    void rejectsNonPositiveDurations() {
        assertThatThrownBy(() -> new EmailVerificationSendLimitProperties(null, 10, EMAIL_WINDOW, 30, IP_WINDOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("send-limit.resend-interval");
        assertThatThrownBy(() -> new EmailVerificationSendLimitProperties(RESEND_INTERVAL, 10, Duration.ZERO, 30, IP_WINDOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("send-limit.email-window");
        assertThatThrownBy(() -> new EmailVerificationSendLimitProperties(RESEND_INTERVAL, 10, EMAIL_WINDOW, 30, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("send-limit.ip-window");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("횟수 한도가 0 이하면 해당 설정 이름을 알리고 거부한다")
    void rejectsNonPositiveMaxRequests(int maxRequests) {
        assertThatThrownBy(() -> new EmailVerificationSendLimitProperties(RESEND_INTERVAL, maxRequests, EMAIL_WINDOW, 30, IP_WINDOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("send-limit.email-max-requests");
        assertThatThrownBy(() -> new EmailVerificationSendLimitProperties(RESEND_INTERVAL, 10, EMAIL_WINDOW, maxRequests, IP_WINDOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("send-limit.ip-max-requests");
    }
}
