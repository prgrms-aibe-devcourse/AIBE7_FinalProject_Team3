package org.example.grab.domain.payment.dto;

import java.time.OffsetDateTime;

public record PaymentResponse(
        Long paymentId,
        Long orderId,
        String orderNumber,
        long amount,
        String status,
        OffsetDateTime paidAt
) {
}
