package org.example.grab.domain.payment.dto;

import jakarta.validation.constraints.NotNull;

public record PaymentRequest(
        @NotNull PaymentMethod paymentMethod,
        @NotNull MockResult mockResult
) {
    public enum PaymentMethod {
        MOCK_CARD
    }

    public enum MockResult {
        SUCCESS,
        FAILURE,
        TIMEOUT
    }
}
