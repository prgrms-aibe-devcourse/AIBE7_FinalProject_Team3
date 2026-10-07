package org.example.grab.domain.drop.event;

import java.util.List;

/**
 * WISH → GRAB 전환이 확정된 DROP 목록(GR-18).
 * 전환 트랜잭션 안에서 발행하고, 커밋된 전환에만 반응하도록 수신 측은 AFTER_COMMIT에서 처리한다.
 * 판매 시작 메일 알림(GR-69)이 이 이벤트를 받는다. DROP 도메인은 수신 측이 무엇을 하는지 알지 않는다.
 */
public record DropGrabStartedEvent(List<Long> dropIds) {

    public DropGrabStartedEvent(List<Long> dropIds) {
        this.dropIds = List.copyOf(dropIds);
    }
}
