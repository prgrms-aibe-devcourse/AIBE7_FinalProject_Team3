package org.example.grab.domain.payment.repository;

import jakarta.persistence.LockModeType;
import org.example.grab.domain.payment.entity.PaymentCancellation;
import org.example.grab.domain.payment.entity.PaymentCancellationPurpose;
import org.example.grab.domain.payment.entity.PaymentCancellationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentCancellationRepository extends JpaRepository<PaymentCancellation, Long> {

    // 주문의 결제에 걸린 취소 요청을 최근 것부터 찾는다. 주문은 order 도메인 소유이므로 결제의 order_id로 잇는다.
    @Query("""
            select c from PaymentCancellation c
            where c.paymentId in (select p.id from Payment p where p.orderId = :orderId)
              and c.purpose = :purpose and c.status in :statuses
            order by c.id desc
            """)
    List<PaymentCancellation> findOfOrder(
            @Param("orderId") Long orderId,
            @Param("purpose") PaymentCancellationPurpose purpose,
            @Param("statuses") Collection<PaymentCancellationStatus> statuses);

    @Query("select c.paymentId from PaymentCancellation c where c.id = :id")
    Optional<Long> findPaymentIdById(@Param("id") Long id);

    // 같은 취소 요청에 결과가 동시에 반영되지 않도록 취소 행을 잠근다. 주문 → 결제 → 취소 순서로 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PaymentCancellation c where c.id = :id")
    Optional<PaymentCancellation> findByIdForUpdate(@Param("id") Long id);
}
