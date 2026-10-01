package org.example.grab.domain.payment.repository;

import org.example.grab.domain.payment.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    boolean existsByPaymentIdAndEventKey(Long paymentId, String eventKey);
}
