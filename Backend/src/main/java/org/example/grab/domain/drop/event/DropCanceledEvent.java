package org.example.grab.domain.drop.event;

/**
 * 판매자가 공개한 WISH DROP을 취소했을 때 발행한다(GR-18, GR-70).
 * 취소 트랜잭션 안에서 발행하고, 수신 측은 AFTER_COMMIT에서 처리해 롤백된 취소에는 반응하지 않는다.
 * WISH 취소 메일 알림(GR-70)이 이 이벤트를 받는다. DROP 도메인은 수신 측이 무엇을 하는지 알지 않는다.
 */
public record DropCanceledEvent(Long dropId) {
}
