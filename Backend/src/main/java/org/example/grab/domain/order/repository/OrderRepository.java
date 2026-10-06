package org.example.grab.domain.order.repository;

import jakarta.persistence.LockModeType;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.dto.PaymentExpiryCandidate;
import org.example.grab.domain.order.dto.SellerOrderListProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 주문 엔티티 저장과 구매자·판매자 주문 조회 쿼리를 제공한다.
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query(value = """
            SELECT o.public_id AS "orderId", o.order_number AS "orderNumber", o.drop_id AS "dropId",
                   o.product_name_snapshot AS "productName", o.seller_name_snapshot AS "sellerName",
                   o.status AS "orderStatus", payment.status AS "paymentStatus",
                   o.items_amount AS "itemsAmount", o.shipping_amount AS "shippingAmount",
                   o.total_amount AS "totalAmount", o.created_at AS "orderedAt"
            FROM orders o
            JOIN drops d ON d.id = o.drop_id AND d.seller_id = :sellerId
            LEFT JOIN LATERAL (
                SELECT p.status
                FROM payments p
                WHERE p.order_id = o.id
                ORDER BY p.created_at DESC, p.id DESC
                LIMIT 1
            ) payment ON TRUE
            WHERE (CAST(:dropId AS BIGINT) IS NULL OR o.drop_id = :dropId)
              AND (CAST(:orderStatus AS VARCHAR) IS NULL OR o.status = :orderStatus)
              AND (CAST(:paymentStatus AS VARCHAR) IS NULL OR payment.status = :paymentStatus)
            ORDER BY o.created_at DESC, o.id DESC
            """, countQuery = """
            SELECT COUNT(*)
            FROM orders o
            JOIN drops d ON d.id = o.drop_id AND d.seller_id = :sellerId
            LEFT JOIN LATERAL (
                SELECT p.status
                FROM payments p
                WHERE p.order_id = o.id
                ORDER BY p.created_at DESC, p.id DESC
                LIMIT 1
            ) payment ON TRUE
            WHERE (CAST(:dropId AS BIGINT) IS NULL OR o.drop_id = :dropId)
              AND (CAST(:orderStatus AS VARCHAR) IS NULL OR o.status = :orderStatus)
              AND (CAST(:paymentStatus AS VARCHAR) IS NULL OR payment.status = :paymentStatus)
            """, nativeQuery = true)
    Page<SellerOrderListProjection> findSellerOrders(
            @Param("sellerId") long sellerId,
            @Param("dropId") Long dropId,
            @Param("orderStatus") String orderStatus,
            @Param("paymentStatus") String paymentStatus,
            Pageable pageable);

    @Query(value = """
            SELECT p.status
            FROM payments p
            WHERE p.order_id = :orderId
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findLatestPaymentStatus(@Param("orderId") Long orderId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM drops WHERE id = :dropId AND seller_id = :sellerId)",
            nativeQuery = true)
    boolean ownsDrop(@Param("sellerId") long sellerId, @Param("dropId") long dropId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM drops WHERE id = :dropId)", nativeQuery = true)
    boolean existsDrop(@Param("dropId") long dropId);

    Optional<Order> findByUuid(UUID uuid);

    Optional<Order> findByUuidAndBuyerId(UUID uuid, Long buyerId);

    Page<Order> findByBuyerIdOrderByCreatedAtDescIdDesc(Long buyerId, Pageable pageable);

    Page<Order> findByBuyerIdAndStatusOrderByCreatedAtDescIdDesc(
            Long buyerId, OrderStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.uuid = :uuid")
    Optional<Order> findByUuidForUpdate(@Param("uuid") UUID uuid);

    // 결제 요청 검증: 같은 주문의 결제 요청을 직렬화해 진행 중인 결제 확인과 PENDING 저장 사이에 다른 요청이 끼지 못하게 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.uuid = :uuid and o.buyerId = :buyerId")
    Optional<Order> findByUuidAndBuyerIdForUpdate(@Param("uuid") UUID uuid, @Param("buyerId") Long buyerId);

    // 결제 확정·만료: 같은 주문 행을 잠가 둘 중 한 경로만 재고를 바꾸게 한다(ERD.md 3.2).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /*
        결제 대기 만료 후보(GR-22, ERD.md 3.2). 잠그지 않고 idx_orders_payment_expiry 순서로 읽는다.
        첫 페이지와 다음 페이지를 나눈 것은 커서 없음 조건을 OR로 섞으면 행 비교가 인덱스 조건으로 쓰이지 않기 때문이다.
     */
    @Query(value = """
            SELECT o.id AS "id", o.payment_expires_at AS "paymentExpiresAt"
            FROM orders o
            WHERE o.status = 'PAYMENT_PENDING'
              AND o.payment_expires_at <= :now
            ORDER BY o.payment_expires_at, o.id
            LIMIT :limit
            """, nativeQuery = true)
    List<PaymentExpiryCandidate> findPaymentExpiryCandidates(
            @Param("now") OffsetDateTime now, @Param("limit") int limit);

    @Query(value = """
            SELECT o.id AS "id", o.payment_expires_at AS "paymentExpiresAt"
            FROM orders o
            WHERE o.status = 'PAYMENT_PENDING'
              AND o.payment_expires_at <= :now
              AND (o.payment_expires_at, o.id) > (:afterExpiresAt, :afterId)
            ORDER BY o.payment_expires_at, o.id
            LIMIT :limit
            """, nativeQuery = true)
    List<PaymentExpiryCandidate> findPaymentExpiryCandidatesAfter(
            @Param("now") OffsetDateTime now,
            @Param("afterExpiresAt") Instant afterExpiresAt,
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    // 결제 대기 만료: 결제 확정 등 다른 트랜잭션이 잠근 주문은 기다리지 않고 빈 결과로 건너뛴다(ERD.md 3.2).
    @Query(value = "SELECT * FROM orders WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Order> findByIdForUpdateSkipLocked(@Param("id") Long id);

    /*
    payments_cancellations에서 해당 주문의 purpose = 'ORDER_CANCEL', status = 'UNKNOWN'인 기록이 있는지 조회
    -> 구매자가 취소를 요청했는데 PG 응답이 끊겨 취소됐는지 모르는 상태(UNKNOWN)
    */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM payment_cancellations pc
                JOIN payments p ON p.id = pc.payment_id
                WHERE p.order_id = :orderId
                  AND pc.purpose = 'ORDER_CANCEL'
                  AND pc.status = 'UNKNOWN'
            )
            """, nativeQuery = true)
    boolean hasUnknownOrderCancellation(@Param("orderId") Long orderId);

    Optional<Order> findByOrderNumber(String orderNumber);

    Optional<Order> findByBuyerIdAndIdempotencyKey(Long buyerId, String idempotencyKey);

    long countByBuyerIdAndIdempotencyKey(Long buyerId, String idempotencyKey);
}
