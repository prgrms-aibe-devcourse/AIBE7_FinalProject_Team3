package org.example.grab.domain.payment.dto;

import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentProvider;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.entity.ReconciliationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

// 구매자에게 주는 결제 시도 한 건(PAYMENT.md 1.3). 판매자용은 PG 실패 사유를 뺀 SellerPaymentHistoryResponse를 쓴다.
public record PaymentHistoryResponse(
        UUID paymentId,
        UUID orderId,
        PaymentProvider provider,
        long amount,
        PaymentStatus status,
        ReconciliationStatus reconciliationStatus,
        OffsetDateTime requestedAt,
        OffsetDateTime paidAt,
        OffsetDateTime canceledAt,
        PaymentResponse.Failure failure
) {

    public static PaymentHistoryResponse of(Payment payment, UUID orderId) {
        PaymentResponse.Failure failure = payment.getStatus() == PaymentStatus.FAILED
                ? new PaymentResponse.Failure(payment.getFailureCode(), payment.getFailureMessage())
                : null;
        return new PaymentHistoryResponse(
                payment.getUuid(),
                orderId,
                payment.getProvider(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getReconciliationStatus(),
                payment.getCreatedAt(),
                payment.getApprovedAt(),
                payment.getCanceledAt(),
                failure
        );
    }
}
