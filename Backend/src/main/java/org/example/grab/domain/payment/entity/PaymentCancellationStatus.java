package org.example.grab.domain.payment.entity;

// PG 결제 취소 요청의 상태(ERD.md 3.3)
public enum PaymentCancellationStatus {
    // 취소 기록을 저장했고 PG 응답을 기다린다
    REQUESTED,
    // PG 취소 결과를 알 수 없다. 같은 서버 멱등 키로 다시 요청해 확정한다
    UNKNOWN,
    SUCCEEDED,
    FAILED
}
