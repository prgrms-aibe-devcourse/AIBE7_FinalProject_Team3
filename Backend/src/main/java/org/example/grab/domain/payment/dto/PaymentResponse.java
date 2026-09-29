package org.example.grab.domain.payment.dto;

import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.entity.ReconciliationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentResponse(
        UUID paymentId,
        UUID orderId,
        String orderNumber,
        long amount,
        PaymentStatus status,
        ReconciliationStatus reconciliationStatus,
        OffsetDateTime paidAt,
        Failure failure
) {

    public static PaymentResponse of(Payment payment, UUID orderId, String orderNumber) {
        Failure failure = payment.getStatus() == PaymentStatus.FAILED
                ? new Failure(payment.getFailureCode(), payment.getFailureMessage())
                : null;
        OffsetDateTime paidAt = payment.getStatus() == PaymentStatus.SUCCEEDED ? payment.getApprovedAt() : null;
        return new PaymentResponse(
                payment.getUuid(),
                orderId,
                orderNumber,
                payment.getAmount(),
                payment.getStatus(),
                payment.getReconciliationStatus(),
                paidAt,
                failure
        );
    }

    public record Failure(String code, String message) {
    }
}
