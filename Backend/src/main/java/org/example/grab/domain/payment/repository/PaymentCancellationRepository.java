package org.example.grab.domain.payment.repository;

import jakarta.persistence.LockModeType;
import org.example.grab.domain.payment.entity.PaymentCancellation;
import org.example.grab.domain.payment.entity.PaymentCancellationPurpose;
import org.example.grab.domain.payment.entity.PaymentCancellationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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

    /*
        결제 취소 재요청 배치의 대상을 한 문장으로 선점한다(ERD.md 3.3, idx_payment_cancellations_retry).
        다른 인스턴스가 잠근 행은 건너뛰고, next_retry_at을 바꿔 다른 인스턴스와 다음 주기가 그때까지 다시 고르지 않게 한다.
        보정 환불(LATE_APPROVAL_COMPENSATION)은 반영 경로가 달라 고르지 않는다(GR-88).
        UPDATE ... RETURNING이라 결과를 읽어야 하므로 @Modifying 없이 조회로 실행한다. 호출하는 쪽의 트랜잭션이 필요하다.
     */
    @Query(value = """
            WITH claimed AS (
                SELECT id FROM payment_cancellations
                WHERE purpose = 'ORDER_CANCEL'
                  AND status IN ('REQUESTED', 'UNKNOWN')
                  AND next_retry_at <= :now
                -- 재요청 시각이 이른 취소부터 선택한다.
                ORDER BY next_retry_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE payment_cancellations c
            SET next_retry_at = :nextRetryAt
            FROM claimed
            WHERE c.id = claimed.id
            RETURNING c.id
            """, nativeQuery = true)
    List<Long> claimOrderCancelRetries(
            @Param("now") OffsetDateTime now,
            @Param("nextRetryAt") OffsetDateTime nextRetryAt,
            @Param("limit") int limit);

    /*
        보정 환불 배치의 환불 요청 대상을 선점한다(GR-88, ERD.md 3.3). 방식은 claimOrderCancelRetries와 같고 purpose만 다르다.
        UPDATE ... RETURNING이라 결과를 읽어야 하므로 @Modifying 없이 조회로 실행한다. 호출하는 쪽의 트랜잭션이 필요하다.
     */
    @Query(value = """
            WITH claimed AS (
                SELECT id FROM payment_cancellations
                WHERE purpose = 'LATE_APPROVAL_COMPENSATION'
                  AND status IN ('REQUESTED', 'UNKNOWN')
                  AND next_retry_at <= :now
                ORDER BY next_retry_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE payment_cancellations c
            SET next_retry_at = :nextRetryAt
            FROM claimed
            WHERE c.id = claimed.id
            RETURNING c.id
            """, nativeQuery = true)
    List<Long> claimCompensationRefunds(
            @Param("now") OffsetDateTime now,
            @Param("nextRetryAt") OffsetDateTime nextRetryAt,
            @Param("limit") int limit);
}
