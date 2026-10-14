package org.example.grab.domain.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 결제 배치를 주기적으로 트리거한다(GR-65·GR-87, ERD.md 3.2).
 * 실제 처리와 단계 순서는 PaymentBatchService에 두고, 여기서는 실행 시각을 만들어 넘기고 예외를 격리하는 역할만 한다.
 * fixedDelay는 이전 실행이 끝난 뒤부터 다음 주기를 세므로 처리가 10초를 넘겨도 실행이 겹치지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "grab.payment-batch.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentBatchScheduler {

    private final PaymentBatchService paymentBatchService;

    @Scheduled(fixedDelayString = "${grab.payment-batch.fixed-delay:10s}")
    public void run() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            paymentBatchService.run(now);
        } catch (Exception e) {
            log.error("결제 배치 실행 실패", e);
        }
    }
}
