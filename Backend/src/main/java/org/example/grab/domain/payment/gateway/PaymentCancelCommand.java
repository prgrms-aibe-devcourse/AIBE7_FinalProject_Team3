package org.example.grab.domain.payment.gateway;

import java.util.Objects;

/**
 * PG 결제 전액 취소 요청.
 *
 * @param cancelReason   PG에 보내는 취소 사유. 토스 제한(200자)을 넘으면 잘라서 보낸다
 * @param idempotencyKey 서버가 만든 PG 취소용 멱등 키(payment_cancellations.idempotency_key)
 */
public record PaymentCancelCommand(
        String paymentKey,
        String cancelReason,
        String idempotencyKey
) {

    public static final int MAX_CANCEL_REASON_LENGTH = 200;

    public PaymentCancelCommand {
        Objects.requireNonNull(paymentKey);
        Objects.requireNonNull(cancelReason);
        Objects.requireNonNull(idempotencyKey);
        if (cancelReason.length() > MAX_CANCEL_REASON_LENGTH) {
            cancelReason = cancelReason.substring(0, MAX_CANCEL_REASON_LENGTH);
        }
    }

    // paymentKey·멱등 키는 결제 식별 값이고 사유는 사용자 입력이므로 로그에 원문을 남기지 않는다(CODING_CONVENTION.md 2.7).
    @Override
    public String toString() {
        return "PaymentCancelCommand[cancelReasonLength=" + cancelReason.length() + "]";
    }
}
