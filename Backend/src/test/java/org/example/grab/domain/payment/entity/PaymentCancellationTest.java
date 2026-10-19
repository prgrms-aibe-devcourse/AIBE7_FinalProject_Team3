package org.example.grab.domain.payment.entity;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentCancellationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-16T12:00:00Z");

    @Test
    @DisplayName("소비자 주문 취소는 요청한 회원을 남기고 1분 뒤부터 재요청 대상이 된다")
    void requestOrderCancel() {
        // when
        PaymentCancellation cancellation = PaymentCancellation.requestOrderCancel(1L, 7L, 33000, "단순 변심", NOW);

        // then
        assertThat(cancellation.getPurpose()).isEqualTo(PaymentCancellationPurpose.ORDER_CANCEL);
        assertThat(cancellation.getRequestedBy()).isEqualTo(7L);
        assertThat(cancellation.getNextRetryAt()).isEqualTo(NOW.plus(PaymentCancellation.RETRY_AFTER));
    }

    @Test
    @DisplayName("보정 환불은 요청자 없이 만들고 같은 주기에 바로 환불 요청 대상이 된다")
    void requestCompensation() {
        // when
        PaymentCancellation cancellation = PaymentCancellation.requestCompensation(1L, 33000, "환불", NOW);

        // then
        assertThat(cancellation.getPurpose()).isEqualTo(PaymentCancellationPurpose.LATE_APPROVAL_COMPENSATION);
        assertThat(cancellation.getRequestedBy()).isNull();
        assertThat(cancellation.getAmount()).isEqualTo(33000);
        assertThat(cancellation.getStatus()).isEqualTo(PaymentCancellationStatus.REQUESTED);
        assertThat(cancellation.getIdempotencyKey()).isNotBlank();
        assertThat(cancellation.getNextRetryAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("PG에 요청하지 않고 끝내면 시도 횟수를 늘리지 않고 FAILED와 실패 코드를 남긴다")
    void abandon() {
        // given
        PaymentCancellation cancellation = PaymentCancellation.requestCompensation(1L, 33000, "환불", NOW);

        // when
        cancellation.abandon("PG_ORDER_MISMATCH", NOW);

        // then
        assertThat(cancellation.getStatus()).isEqualTo(PaymentCancellationStatus.FAILED);
        assertThat(cancellation.getFailureCode()).isEqualTo("PG_ORDER_MISMATCH");
        assertThat(cancellation.getAttemptCount()).isZero();
        assertThat(cancellation.getCompletedAt()).isEqualTo(NOW);
        assertThat(cancellation.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("이미 끝난 취소는 다시 끝낼 수 없다")
    void rejectsAbandonAfterSettled() {
        // given
        PaymentCancellation cancellation = PaymentCancellation.requestCompensation(1L, 33000, "환불", NOW);
        cancellation.succeed("tx-key", NOW);

        // when & then
        assertThatThrownBy(() -> cancellation.abandon("PG_ORDER_MISMATCH", NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION);
    }
}
