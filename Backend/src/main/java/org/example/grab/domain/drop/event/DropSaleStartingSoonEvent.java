package org.example.grab.domain.drop.event;

import java.time.Duration;
import java.util.List;

/**
 * 판매 시작이 임박한 DROP 목록(GR-69). 알림 발송 기록 선점에 성공한 DROP만 담긴다.
 * 선점 트랜잭션 안에서 발행하고, 커밋된 선점에만 반응하도록 수신 측은 AFTER_COMMIT에서 처리한다.
 */
public record DropSaleStartingSoonEvent(List<Long> dropIds) {

    /** 판매 시작 몇 분 전에 알릴지. 대상 조회 조건과 메일 문구가 같은 값을 써야 하므로 여기 한 곳에 둔다. */
    public static final Duration LEAD = Duration.ofMinutes(10);

    public DropSaleStartingSoonEvent(List<Long> dropIds) {
        this.dropIds = List.copyOf(dropIds);
    }
}
