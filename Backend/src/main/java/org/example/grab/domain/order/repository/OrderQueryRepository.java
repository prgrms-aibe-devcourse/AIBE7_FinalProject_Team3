package org.example.grab.domain.order.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderQueryRepository {

    private final JdbcClient jdbcClient;

    public OrderQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public String findLatestPaymentStatus(Long orderId) {
        return jdbcClient.sql("""
                        SELECT status
                        FROM payments
                        WHERE order_id = :orderId
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
