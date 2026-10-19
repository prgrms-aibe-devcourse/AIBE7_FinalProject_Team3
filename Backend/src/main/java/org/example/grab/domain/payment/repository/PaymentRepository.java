package org.example.grab.domain.payment.repository;

import jakarta.persistence.LockModeType;
import org.example.grab.domain.payment.entity.Payment;
import org.example.grab.domain.payment.entity.PaymentProvider;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderIdAndClientIdempotencyKey(Long orderId, String clientIdempotencyKey);

    // 진행 중인 결제(PENDING, UNKNOWN)가 있는지 확인해 같은 주문의 이중 결제를 막는다.
    List<Payment> findByOrderIdAndStatusInOrderByIdAsc(Long orderId, Collection<PaymentStatus> statuses);

    // 주문 결제 이력을 시도 순서대로 읽는다(PAYMENT.md 1.3). uq_payments_order_client_idempotency가 order_id로 시작해 인덱스를 탄다.
    List<Payment> findByOrderIdOrderByIdAsc(Long orderId);

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

    /*
        진행 중 결제 정리 배치의 대상을 한 문장으로 선점한다(ERD.md 3.2, idx_payments_in_progress).
        다른 인스턴스가 잠근 행은 건너뛰고, updated_at을 now로 바꿔 다른 인스턴스와 다음 주기가 60초 동안 다시 고르지 않게 한다.
        UPDATE ... RETURNING이라 결과를 읽어야 하므로 @Modifying 없이 조회로 실행한다. 호출하는 쪽의 트랜잭션이 필요하다.
     */
    @Query(value = """
            WITH claimed AS (
                SELECT id FROM payments 
                WHERE status IN ('PENDING', 'UNKNOWN') -- 결과를 아직 확정하지 못한 결제만 선택한다.
                -- 최근 변경된 결제는 제외한다.
                -- 호출부가 now - 60초를 전달하므로,
                -- 마지막 변경 후 60초 이상 지난 결제가 대상이다.
                  AND updated_at <= :staleBefore
                -- 오래 변경되지 않은 결제부터 선택한다.
                -- 시간이 같으면 ID로 순서를 정한다.
                ORDER BY updated_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            -- 위에서 선택한 결제의 마지막 변경 시각을 갱신.
            UPDATE payments p
            SET updated_at = :now
            FROM claimed
            WHERE p.id = claimed.id
            -- 실제 갱신한 결제의 ID를 반환.
            RETURNING p.id
            """, nativeQuery = true)
    List<Long> claimInProgressForReconciliation(
            @Param("staleBefore") OffsetDateTime staleBefore,
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit);

    /*
        보정 환불 기록 생성의 대상 결제를 잠근다(GR-88, ERD.md 3.3, idx_payments_reconciliation).
        주문을 확정하지 못한 승인(SUCCEEDED, 보정 REQUIRED) 중 보정 환불 기록이 아직 없는 결제만 고른다.
        다른 인스턴스가 잠근 행은 건너뛴다. 잠금은 호출하는 쪽의 트랜잭션이 끝날 때까지 유지되므로 그 안에서 기록을 만든다.
     */
    @Query(value = """
            SELECT p.id FROM payments p
            WHERE p.reconciliation_status = 'REQUIRED'
              AND p.status = 'SUCCEEDED'
              -- 거절·주문번호 불일치로 끝난 기록이 있는 결제도 다시 고르지 않는다(운영자 확인).
              AND NOT EXISTS (
                  SELECT 1 FROM payment_cancellations c
                  WHERE c.payment_id = p.id
                    AND c.purpose = 'LATE_APPROVAL_COMPENSATION'
              )
            ORDER BY p.updated_at, p.id
            LIMIT :limit
            FOR UPDATE OF p SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockCompensationTargets(@Param("limit") int limit);
}
