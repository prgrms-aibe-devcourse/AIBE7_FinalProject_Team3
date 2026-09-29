package org.example.grab.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 토스페이먼츠 결제창 successUrl로 받은 값(PAYMENT.md 1.1).
 */
public record PaymentRequest(
        @NotBlank @Size(max = 200) String paymentKey,
        @NotNull @Positive Long amount
) {

    // paymentKey는 결제 식별 키이므로 로그에 원문을 남기지 않는다(CODING_CONVENTION.md 2.7).
    @Override
    public String toString() {
        return "PaymentRequest[amount=" + amount + "]";
    }
}
