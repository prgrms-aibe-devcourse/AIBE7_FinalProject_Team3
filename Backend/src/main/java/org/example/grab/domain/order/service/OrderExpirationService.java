package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.repository.OrderExpirationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderExpirationService {

    private static final int BATCH_SIZE = 100;

    private final OrderExpirationRepository orderExpirationRepository;
    private final Clock clock;

    @Transactional
    public int expirePaymentPendingOrders() {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        List<Long> orderIds = orderExpirationRepository.lockExpiredOrderIds(now, BATCH_SIZE);
        orderIds.forEach(orderId -> orderExpirationRepository.expire(orderId, now));
        return orderIds.size();
    }
}
