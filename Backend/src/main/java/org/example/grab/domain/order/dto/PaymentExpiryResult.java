package org.example.grab.domain.order.dto;

// 결제 대기 만료 배치가 주문 한 건을 처리한 결과
public enum PaymentExpiryResult {
    // 주문을 만료하고 선점 재고를 가용 재고로 되돌렸다
    EXPIRED,
    // 결제 확정 등 다른 트랜잭션이 주문을 잠그고 있어 건너뛰었다. 다음 실행에서 다시 조회된다
    SKIPPED_LOCKED,
    // 이미 결제·만료·취소됐거나 마감 전이라 만료 대상이 아니다
    NOT_DUE
}
