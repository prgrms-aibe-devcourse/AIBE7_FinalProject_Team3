package org.example.grab.domain.order.repository;

import org.example.grab.domain.order.dto.OrderSummaryResponse;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.global.common.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class SellerOrderRepository {

    private final JdbcClient jdbcClient;

    public SellerOrderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean ownsOrder(Long sellerUserId, Long orderId) {
        return jdbcClient.sql("""
                        SELECT 1
                        FROM orders o
                        JOIN drops d ON d.id = o.drop_id
                        JOIN sellers s ON s.id = d.seller_id
                        WHERE o.id = :orderId AND s.user_id = :sellerUserId AND s.status = 'APPROVED'
                        """)
                .param("orderId", orderId)
                .param("sellerUserId", sellerUserId)
                .query(Integer.class).optional().isPresent();
    }

    public boolean hasUnknownCancellation(Long orderId) {
        return jdbcClient.sql("""
                        SELECT 1
                        FROM payment_cancellations pc
                        JOIN payments p ON p.id = pc.payment_id
                        WHERE p.order_id = :orderId AND pc.status = 'UNKNOWN'
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .query(Integer.class).optional().isPresent();
    }

    public PageResponse<OrderSummaryResponse> findOrders(
            Long sellerUserId,
            Long dropId,
            String orderStatus,
            String paymentStatus,
            int page,
            int size
    ) {
        String filters = """
                FROM orders o
                JOIN drops d ON d.id = o.drop_id
                JOIN sellers s ON s.id = d.seller_id
                LEFT JOIN LATERAL (
                    SELECT p.status
                    FROM payments p
                    WHERE p.order_id = o.id
                    ORDER BY p.created_at DESC, p.id DESC
                    LIMIT 1
                ) latest_payment ON TRUE
                WHERE s.user_id = :sellerUserId AND s.status = 'APPROVED'
                  AND (:dropId IS NULL OR o.drop_id = :dropId)
                  AND (:orderStatus IS NULL OR o.status = :orderStatus)
                  AND (:paymentStatus IS NULL OR latest_payment.status = :paymentStatus)
                """;
        List<OrderSummaryResponse> content = bindFilters(jdbcClient.sql("""
                        SELECT o.id, o.order_number, o.status, o.total_amount, o.created_at
                        """ + filters + """
                        ORDER BY o.created_at DESC, o.id DESC
                        LIMIT :size OFFSET :offset
                        """), sellerUserId, dropId, orderStatus, paymentStatus)
                .param("size", size).param("offset", page * size)
                .query((rs, rowNum) -> new OrderSummaryResponse(
                        rs.getLong("id"), rs.getString("order_number"),
                        OrderStatus.valueOf(rs.getString("status")), rs.getLong("total_amount"),
                        rs.getObject("created_at", OffsetDateTime.class)
                )).list();
        long total = bindFilters(jdbcClient.sql("SELECT COUNT(*) " + filters),
                sellerUserId, dropId, orderStatus, paymentStatus)
                .query(Long.class).single();
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(content, page, size, total, totalPages, page + 1 < totalPages);
    }

    private JdbcClient.StatementSpec bindFilters(
            JdbcClient.StatementSpec spec,
            Long sellerUserId,
            Long dropId,
            String orderStatus,
            String paymentStatus
    ) {
        return spec.param("sellerUserId", sellerUserId)
                .param("dropId", dropId, java.sql.Types.BIGINT)
                .param("orderStatus", orderStatus, java.sql.Types.VARCHAR)
                .param("paymentStatus", paymentStatus, java.sql.Types.VARCHAR);
    }
}
