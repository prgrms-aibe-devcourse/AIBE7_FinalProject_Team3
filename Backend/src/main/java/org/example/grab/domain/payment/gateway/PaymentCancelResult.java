package org.example.grab.domain.payment.gateway;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * PG 결제 취소 결과.
 *
 * @param pgStatus       PG가 돌려준 결제 상태 원문(예: CANCELED). 오류 응답·통신 실패면 null
 * @param transactionKey PG 취소 거래 키(payment_cancellations.provider_cancel_id). 취소 성공일 때만 있다
 * @param canceledAt     PG 취소 시각. 취소 성공일 때만 있다
 */
public record PaymentCancelResult(
        Outcome outcome,
        String pgStatus,
        String transactionKey,
        OffsetDateTime canceledAt,
        String failureCode,
        String failureMessage
) {

    public enum Outcome {
        // PG가 결제를 전액 취소했다.
        CANCELED,
        // PG가 취소하지 않았음이 확실하다. 주문을 그대로 두고 새 취소 요청을 받아도 된다.
        REJECTED,
        // 취소 여부를 알 수 없다. 같은 멱등 키로 다시 요청해 확정한다.
        UNKNOWN
    }

    public PaymentCancelResult {
        Objects.requireNonNull(outcome);
        if (outcome == Outcome.CANCELED) {
            Objects.requireNonNull(canceledAt);
        }
    }

    public static PaymentCancelResult canceled(String pgStatus, String transactionKey, OffsetDateTime canceledAt) {
        return new PaymentCancelResult(Outcome.CANCELED, pgStatus, transactionKey, canceledAt, null, null);
    }

    public static PaymentCancelResult rejected(String failureCode, String failureMessage) {
        return new PaymentCancelResult(Outcome.REJECTED, null, null, null, failureCode, failureMessage);
    }

    public static PaymentCancelResult unknown(String pgStatus, String failureCode, String failureMessage) {
        return new PaymentCancelResult(Outcome.UNKNOWN, pgStatus, null, null, failureCode, failureMessage);
    }
}
