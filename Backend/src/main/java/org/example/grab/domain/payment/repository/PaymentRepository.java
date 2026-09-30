package org.example.grab.domain.payment.repository;

import jakarta.persistence.LockModeType;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentProvider;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderIdAndClientIdempotencyKey(Long orderId, String clientIdempotencyKey);

    // 진행 중인 결제(PENDING, UNKNOWN)가 있는지 확인해 같은 주문의 이중 결제를 막는다.
    List<Payment> findByOrderIdAndStatusInOrderByIdAsc(Long orderId, Collection<PaymentStatus> statuses);

    boolean existsByProviderAndProviderPaymentId(PaymentProvider provider, String providerPaymentId);

    // 승인됐지만 주문을 확정하지 못한 결제가 있으면 주문이 결제 대기로 남아도 새 결제를 받지 않는다.
    boolean existsByOrderIdAndStatus(Long orderId, PaymentStatus status);

    // 잠그기 전에 주문 ID만 읽는다. 엔티티로 읽으면 영속성 컨텍스트에 남은 옛 상태가 잠금 조회 결과를 대신한다.
    @Query("select p.orderId from Payment p where p.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);

    // 같은 결제 시도에 승인 응답과 상태 조회 결과가 동시에 반영되지 않도록 결제 행을 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);
}
