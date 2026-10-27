package org.example.grab.domain.demand.dto;

// 종료 DROP 하나의 판매 실적(DEMAND.md 5.1). referencePrice는 준비 수량 가중 평균 단가다.
// dropId는 옵션별 배분(7절)이 같은 표본의 옵션을 읽는 데만 쓰고 응답에는 넣지 않는다
public record SalesSample(long dropId, long sellerId, long referencePrice, int prepared, int sold, boolean soldOut) {
}
