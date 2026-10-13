package org.example.grab.domain.shipment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 배송 단계 Mock 자동 진행을 주기적으로 트리거한다(GR-86).
 * Mock 배송 완료 엔드포인트(MockDeliveryController)와 같은 플래그로 켠다 — 둘 다 실제 택배사 연동이 없을 때만 쓰는 기능이다.
 * DropTransitionScheduler와 같은 이유로 실행 시각만 만들어 넘기고 예외를 격리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "grab.mock-delivery", name = "enabled", havingValue = "true")
public class ShipmentProgressScheduler {

    private final ShipmentProgressService shipmentProgressService;

    @Scheduled(fixedDelayString = "${grab.mock-delivery.poll-interval:10s}")
    public void progress() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            shipmentProgressService.progress(now);
        } catch (Exception e) {
            log.error("배송 단계 Mock 자동 진행 실행 실패", e);
        }
    }
}
