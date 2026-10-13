package org.example.grab.domain.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 결제 대기 배치를 주기적으로 트리거한다(GR-65, ERD.md 3.2).
 * 실제 처리는 각 서비스에 두고, 여기서는 실행 시각을 만들어 넘기고 예외를 격리하는 역할만 한다.
 * fixedDelay는 이전 실행이 끝난 뒤부터 다음 주기를 세므로 처리가 10초를 넘겨도 실행이 겹치지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "grab.payment-pending-batch.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentPendingBatchScheduler {

    private final PaymentInProgressCleanupService paymentInProgressCleanupService;
    private final PaymentExpiryService paymentExpiryService;

    /*
        진행 중 결제 정리를 만료보다 먼저 한다. 마감 전에 승인된 결제를 먼저 주문 PAID로 반영해,
        만료된 뒤 결제 SUCCEEDED·보정 REQUIRED로 남는 경우를 줄인다. 정리가 실패해도 만료는 실행한다.
     */
    @Scheduled(fixedDelayString = "${grab.payment-pending-batch.fixed-delay:10s}")
    public void run() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            paymentInProgressCleanupService.cleanUp(now);
        } catch (Exception e) {
            log.error("진행 중 결제 정리 실행 실패", e);
        }
        try {
            paymentExpiryService.expire(now);
        } catch (Exception e) {
            log.error("결제 대기 만료 실행 실패", e);
        }
    }
}
