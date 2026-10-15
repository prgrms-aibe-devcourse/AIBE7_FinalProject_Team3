package org.example.grab.domain.payment.dto;

import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentProvider;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.entity.ReconciliationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

// 판매자에게 주는 결제 시도 한 건(PAYMENT.md 1.4). PG 실패 코드·메시지는 구매자의 결제 정보라 필드 자체를 두지 않는다.
public record SellerPaymentHistoryResponse(
        UUID paymentId,
        UUID orderId,
        PaymentProvider provider,
        long amount,
        PaymentStatus status,
        ReconciliationStatus reconciliationStatus,
        OffsetDateTime requestedAt,
        OffsetDateTime paidAt,
        OffsetDateTime canceledAt
) {

    public static SellerPaymentHistoryResponse of(Payment payment, UUID orderId) {
        return new SellerPaymentHistoryResponse(
                payment.getUuid(),
                orderId,
                payment.getProvider(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getReconciliationStatus(),
                payment.getCreatedAt(),
                payment.getApprovedAt(),
                payment.getCanceledAt()
        );
    }
}
