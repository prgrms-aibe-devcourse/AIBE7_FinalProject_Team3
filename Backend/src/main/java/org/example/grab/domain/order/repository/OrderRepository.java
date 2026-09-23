package org.example.grab.domain.order.repository;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.dto.SellerOrderListResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 주문 엔티티 저장과 판매자 주문 목록 조회 쿼리를 제공한다.
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query(value = """
            SELECT o.public_id AS "orderId", o.order_number AS "orderNumber", o.drop_id AS "dropId",
                   o.product_name_snapshot AS "productName", o.seller_name_snapshot AS "sellerName",
                   o.status AS "orderStatus", payment.status AS "paymentStatus",
                   o.items_amount AS "itemsAmount", o.shipping_amount AS "shippingAmount",
                   o.total_amount AS "totalAmount", o.created_at AS "orderedAt"
            FROM orders o
            JOIN drops d ON d.id = o.drop_id
            JOIN sellers s ON s.id = d.seller_id AND s.status = 'APPROVED'
            JOIN users u ON u.id = s.user_id AND u.email = :email AND u.status = 'ACTIVE'
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
            JOIN drops d ON d.id = o.drop_id
            JOIN sellers s ON s.id = d.seller_id AND s.status = 'APPROVED'
            JOIN users u ON u.id = s.user_id AND u.email = :email AND u.status = 'ACTIVE'
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
    Page<SellerOrderListResponse> findSellerOrders(
            @Param("email") String email,
            @Param("dropId") Long dropId,
            @Param("orderStatus") String orderStatus,
            @Param("paymentStatus") String paymentStatus,
            Pageable pageable);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM sellers s JOIN users u ON u.id = s.user_id
                WHERE u.email = :email AND u.status = 'ACTIVE' AND s.status = 'APPROVED'
            )
            """, nativeQuery = true)
    boolean existsApprovedSeller(@Param("email") String email);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM drops d
                JOIN sellers s ON s.id = d.seller_id AND s.status = 'APPROVED'
                JOIN users u ON u.id = s.user_id AND u.status = 'ACTIVE'
                WHERE u.email = :email AND d.id = :dropId
            )
            """, nativeQuery = true)
    boolean ownsDrop(@Param("email") String email, @Param("dropId") long dropId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM drops WHERE id = :dropId)", nativeQuery = true)
    boolean existsDrop(@Param("dropId") long dropId);
}
