package org.example.grab.domain.demand.dto;

// 종료 DROP 하나의 판매 실적(DEMAND.md 5.1). referencePrice는 준비 수량 가중 평균 단가다
public record SalesSample(long sellerId, long referencePrice, int prepared, int sold, boolean soldOut) {
}
