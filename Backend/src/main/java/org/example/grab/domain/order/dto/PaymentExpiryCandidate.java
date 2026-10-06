package org.example.grab.domain.order.dto;

import java.time.Instant;

/*
    결제 대기 만료 후보. 잠그지 않고 읽은 값이므로 만료할 때 주문을 잠그고 조건을 다시 확인한다. 마지막 후보가 다음 조회의 커서가 된다.
    네이티브 조회는 TIMESTAMPTZ를 Instant로 돌려주므로 마감 시각을 Instant로 받는다.
 */
public interface PaymentExpiryCandidate {

    Long getId();

    Instant getPaymentExpiresAt();
}
