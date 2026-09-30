package org.example.grab.domain.order.dto;

// PG 승인 성공을 주문에 반영한 결과. COMPLETED가 아니면 주문·예약·재고는 바뀌지 않았고, 결제는 보정 대상이 된다.
public enum PaymentCompletionResult {
    COMPLETED,
    // 결제 마감이 지났거나 이미 만료된 주문(PAY-004)
    EXPIRED,
    // PG 승인 금액이 주문 금액과 다르다(PAY-002)
    AMOUNT_MISMATCH,
    // 이미 결제됐거나 취소되는 등 결제 대기 상태가 아니다
    NOT_PAYABLE,
    // 옵션의 선점 수량이 예약 수량보다 적어 재고 원장이 어긋났다
    INVENTORY_INCONSISTENT
}
