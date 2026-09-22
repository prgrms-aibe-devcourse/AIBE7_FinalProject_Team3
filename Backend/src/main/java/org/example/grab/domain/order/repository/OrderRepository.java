package org.example.grab.domain.order.repository;

import org.example.grab.domain.order.entity.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.example.grab.domain.order.entity.OrderStatus;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    Optional<Order> findByBuyerIdAndIdempotencyKey(Long buyerId, String idempotencyKey);

    @EntityGraph(attributePaths = "items")
    @Query("SELECT o FROM Order o WHERE o.buyerId = :buyerId AND o.idempotencyKey = :idempotencyKey")
    Optional<Order> findWithItemsByBuyerIdAndIdempotencyKey(
            @Param("buyerId") Long buyerId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);

    @Query("""
            SELECT o FROM Order o
            WHERE o.buyerId = :buyerId
              AND (:status IS NULL OR o.status = :status)
            ORDER BY o.createdAt DESC, o.id DESC
            """)
    Page<Order> findBuyerOrders(
            @Param("buyerId") Long buyerId,
            @Param("status") OrderStatus status,
            Pageable pageable
    );

    @EntityGraph(attributePaths = "items")
    @Query("SELECT o FROM Order o WHERE o.id = :orderId")
    Optional<Order> findWithItemsById(@Param("orderId") Long orderId);
}
