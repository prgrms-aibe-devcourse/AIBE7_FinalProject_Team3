package org.example.grab.domain.payment.entity;

// 결제를 처리한 PG사. 결제는 토스페이먼츠 테스트 환경만 사용하며 MOCK은 스키마 호환용으로 남긴다.
public enum PaymentProvider {
    MOCK,
    TOSS
}
