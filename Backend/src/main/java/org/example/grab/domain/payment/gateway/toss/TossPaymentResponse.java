package org.example.grab.domain.payment.gateway.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

// 토스페이먼츠 Payment 객체 중 결과 판단에 필요한 필드만 읽는다. 카드번호 등 나머지 필드는 받지 않는다.
@JsonIgnoreProperties(ignoreUnknown = true)
record TossPaymentResponse(
        String orderId,
        String status,
        Long totalAmount,
        OffsetDateTime approvedAt
) {
}
