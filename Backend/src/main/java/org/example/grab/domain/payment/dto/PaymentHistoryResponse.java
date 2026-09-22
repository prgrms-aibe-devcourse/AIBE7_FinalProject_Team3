package org.example.grab.domain.payment.dto;

import java.time.OffsetDateTime;

public record PaymentHistoryResponse(
        Long paymentId,
        Long orderId,
        String paymentMethod,
        long amount,
        String status,
        String reconciliationStatus,
        OffsetDateTime approvedAt,
        OffsetDateTime createdAt
) {
}
