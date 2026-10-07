package org.example.grab.domain.drop.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * DROP 상태 자동 전환과 판매 임박 알림 선점을 주기적으로 트리거한다(GR-18, GR-69).
 * 실제 로직은 DropTransitionService에 두고, 여기서는 실행 시각을 만들어 넘기고 예외를 격리하는 역할만 한다.
 * fixedDelay는 이전 실행이 끝난 뒤부터 다음 주기를 세므로 처리가 10초를 넘겨도 실행이 겹치지 않는다.
 * 이 주기가 알림 지연의 상한이다. 판매 시작 알림은 최대 한 주기 늦고, 임박 알림도 그만큼 일찍 나갈 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "grab.drop-transition.enabled", havingValue = "true", matchIfMissing = true)
public class DropTransitionScheduler {

    private final DropTransitionService dropTransitionService;

    @Scheduled(fixedDelayString = "${grab.drop-transition.fixed-delay:10s}")
    public void transition() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            dropTransitionService.transition(now);
        } catch (Exception e) {
            log.error("DROP 상태 전환 실행 실패", e);
        }
    }
}
