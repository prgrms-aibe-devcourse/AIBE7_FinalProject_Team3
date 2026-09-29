package org.example.grab.domain.payment.gateway;

import java.util.Objects;

/**
 * PG 승인 요청.
 *
 * @param orderId        PG에 보내는 주문 식별자(서버 주문의 orderNumber)
 * @param idempotencyKey 서버가 만든 PG 승인용 멱등 키(payments.idempotency_key)
 */
public record PaymentConfirmCommand(
        String paymentKey,
        String orderId,
        long amount,
        String idempotencyKey
) {

    public PaymentConfirmCommand {
        Objects.requireNonNull(paymentKey);
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(idempotencyKey);
    }

    // paymentKey는 결제 식별 키이므로 로그에 원문을 남기지 않는다(CODING_CONVENTION.md 2.7).
    @Override
    public String toString() {
        return "PaymentConfirmCommand[orderId=" + orderId + ", amount=" + amount + "]";
    }
}
