package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpirationScheduler {

    private final OrderExpirationService orderExpirationService;

    @Scheduled(fixedDelayString = "${order.expiration.fixed-delay:30000}")
    public void expireOrders() {
        int expiredCount = orderExpirationService.expirePaymentPendingOrders();
        if (expiredCount > 0) {
            log.info("결제 대기 주문 만료 처리 완료: count={}", expiredCount);
        }
    }
}
