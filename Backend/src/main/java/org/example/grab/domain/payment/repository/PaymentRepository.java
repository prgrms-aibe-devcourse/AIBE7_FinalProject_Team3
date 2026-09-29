package org.example.grab.domain.payment.repository;

import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentProvider;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderIdAndClientIdempotencyKey(Long orderId, String clientIdempotencyKey);

    // 진행 중인 결제(PENDING, UNKNOWN)가 있는지 확인해 같은 주문의 이중 결제를 막는다.
    List<Payment> findByOrderIdAndStatusInOrderByIdAsc(Long orderId, Collection<PaymentStatus> statuses);

    boolean existsByProviderAndProviderPaymentId(PaymentProvider provider, String providerPaymentId);
}
