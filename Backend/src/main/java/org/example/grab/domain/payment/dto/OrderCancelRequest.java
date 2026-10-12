package org.example.grab.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 소비자 주문 취소 요청(ORDER.md 1.4).
 */
public record OrderCancelRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
