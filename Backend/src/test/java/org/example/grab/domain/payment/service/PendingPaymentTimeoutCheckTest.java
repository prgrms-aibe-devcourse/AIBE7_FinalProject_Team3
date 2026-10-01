package org.example.grab.domain.payment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingPaymentTimeoutCheckTest {

    private static final Duration STALE_AFTER = PaymentTransactionService.STALE_PENDING_AFTER;

    @Test
    @DisplayName("기본 타임아웃(연결 3초, 응답 10초)이면 승인·조회 최대 26초가 정리 기준보다 짧아 통과한다")
    void passesWithDefaultTimeouts() {
        assertThatCode(() -> PendingPaymentTimeoutCheck.verify(
                Duration.ofSeconds(3), Duration.ofSeconds(10), STALE_AFTER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("응답 타임아웃만 늘려 승인·조회 최대 대기 시간이 정리 기준 이상이 되면 기동을 막는다")
    void failsWhenTimeoutsReachStaleThreshold() {
        assertThatThrownBy(() -> PendingPaymentTimeoutCheck.verify(
                Duration.ofSeconds(3), Duration.ofSeconds(30), STALE_AFTER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("(66s)");
        // 정확히 같아도 경계에서 겹치므로 막는다.
        assertThatThrownBy(() -> PendingPaymentTimeoutCheck.verify(
                Duration.ofSeconds(10), Duration.ofSeconds(20), STALE_AFTER))
                .isInstanceOf(IllegalStateException.class);
    }
}
