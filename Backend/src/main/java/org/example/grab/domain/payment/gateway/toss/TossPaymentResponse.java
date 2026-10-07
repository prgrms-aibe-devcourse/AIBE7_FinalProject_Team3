package org.example.grab.domain.payment.gateway.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;

// 토스페이먼츠 Payment 객체 중 결과 판단에 필요한 필드만 읽는다. 카드번호 등 나머지 필드는 받지 않는다.
@JsonIgnoreProperties(ignoreUnknown = true)
record TossPaymentResponse(
        String orderId,
        String status,
        Long totalAmount,
        OffsetDateTime approvedAt,
        List<Cancel> cancels
) {

    // 취소 이력 한 건. cancelStatus가 DONE이면 취소가 완료된 것이다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cancel(
            String transactionKey,
            String cancelStatus,
            OffsetDateTime canceledAt
    ) {
    }
}
