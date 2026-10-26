package org.example.grab.domain.drop.event;

/**
 * 판매자가 DRAFT를 WISH로 공개했을 때 발행한다(GR-167).
 * 공개 트랜잭션 안에서 발행하고, 수신 측은 AFTER_COMMIT에서 처리해 롤백된 공개에는 반응하지 않는다.
 * 공개 시점 재고 추천 기록(수요 분석 API 5.6)이 이 이벤트를 받는다. DROP 도메인은 수신 측이 무엇을 하는지 알지 않는다.
 */
public record DropPublishedEvent(Long dropId) {
}
