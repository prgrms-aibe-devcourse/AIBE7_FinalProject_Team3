package org.example.grab.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.repository.LatePaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class LatePaymentReconciliationService {

    private final LatePaymentRepository latePaymentRepository;
    private final Clock clock;

    @Transactional
    public boolean recordIfOrderExpired(Long paymentId) {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        return latePaymentRepository.markReconciliationRequiredIfOrderExpired(paymentId, now);
    }
}
