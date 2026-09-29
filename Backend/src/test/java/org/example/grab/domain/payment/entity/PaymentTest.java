package org.example.grab.domain.payment.entity;

import org.example.grab.domain.order.entity.PaymentStatus;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2026-09-29T12:00:00Z");

    @Test
    @DisplayName("결제 요청은 PENDING 상태와 클라이언트 키와 다른 서버 PG 키로 만든다")
    void request() {
        // when
        Payment payment = createPayment();

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getReconciliationStatus()).isEqualTo(ReconciliationStatus.NONE);
        assertThat(payment.getProvider()).isEqualTo(PaymentProvider.TOSS);
        assertThat(payment.getProviderPaymentId()).isEqualTo("payment-key");
        assertThat(payment.getAmount()).isEqualTo(33000);
        assertThat(payment.getIdempotencyKey()).isNotBlank().isNotEqualTo(payment.getClientIdempotencyKey());
        assertThat(payment.isInProgress()).isTrue();
    }

    @Test
    @DisplayName("승인 성공은 SUCCEEDED와 승인 시각을 기록하고 진행 중이 아니게 된다")
    void succeed() {
        // given
        Payment payment = createPayment();

        // when
        payment.succeed(APPROVED_AT);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getApprovedAt()).isEqualTo(APPROVED_AT);
        assertThat(payment.getReconciliationStatus()).isEqualTo(ReconciliationStatus.NONE);
        assertThat(payment.isInProgress()).isFalse();
    }

    @Test
    @DisplayName("승인 거절은 FAILED와 실패 정보를 기록하고 실패 코드는 컬럼 길이로 자른다")
    void fail() {
        // given
        Payment payment = createPayment();

        // when
        payment.fail("C".repeat(120), "카드 승인이 거절되었습니다.");

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).hasSize(100);
        assertThat(payment.getFailureMessage()).isEqualTo("카드 승인이 거절되었습니다.");
        assertThat(payment.isInProgress()).isFalse();
    }

    @Test
    @DisplayName("결과를 확인하지 못하면 UNKNOWN과 보정 필요로 기록하고 진행 중으로 남는다")
    void markUnknown() {
        // given
        Payment payment = createPayment();

        // when
        payment.markUnknown("승인 응답 타임아웃");

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(payment.getReconciliationStatus()).isEqualTo(ReconciliationStatus.REQUIRED);
        assertThat(payment.getReconciliationReason()).isEqualTo("승인 응답 타임아웃");
        assertThat(payment.isInProgress()).isTrue();
    }

    @Test
    @DisplayName("UNKNOWN을 조회로 확정하면 보정 상태를 RESOLVED로 바꾼다")
    void resolveUnknown() {
        // given
        Payment succeeded = createPayment();
        Payment failed = createPayment();
        succeeded.markUnknown("승인 응답 타임아웃");
        failed.markUnknown("승인 응답 타임아웃");

        // when
        succeeded.succeed(APPROVED_AT);
        failed.fail("NOT_FOUND_PAYMENT", "승인되지 않은 결제입니다.");

        // then
        assertThat(succeeded.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(succeeded.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RESOLVED);
    }

    @Test
    @DisplayName("주문을 확정할 수 없는 승인 성공은 SUCCEEDED와 보정 필요로 기록한다")
    void succeedRequiringReconciliation() {
        // given
        Payment payment = createPayment();

        // when
        payment.succeedRequiringReconciliation(APPROVED_AT, "결제 마감 후 승인");

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getApprovedAt()).isEqualTo(APPROVED_AT);
        assertThat(payment.getReconciliationStatus()).isEqualTo(ReconciliationStatus.REQUIRED);
        assertThat(payment.getReconciliationReason()).isEqualTo("결제 마감 후 승인");
    }

    @Test
    @DisplayName("결과가 확정된 결제는 다시 바꿀 수 없다")
    void rejectsTransitionAfterResolved() {
        // given
        Payment payment = createPayment();
        payment.fail("REJECT_CARD_PAYMENT", "카드 승인이 거절되었습니다.");

        // when & then
        assertThatThrownBy(() -> payment.succeed(APPROVED_AT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION);
        assertThatThrownBy(() -> payment.markUnknown("재시도"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_STATE_TRANSITION);
    }

    private Payment createPayment() {
        return Payment.request(1L, PaymentProvider.TOSS, "client-key", "a".repeat(64), "payment-key", 33000);
    }
}
