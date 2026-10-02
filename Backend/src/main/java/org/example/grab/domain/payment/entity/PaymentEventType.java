package org.example.grab.domain.payment.entity;

// 결제 이벤트 종류. 승인 요청 응답과 상태 확인용 조회 응답을 구분한다.
public enum PaymentEventType {
    CONFIRM,
    LOOKUP
}
