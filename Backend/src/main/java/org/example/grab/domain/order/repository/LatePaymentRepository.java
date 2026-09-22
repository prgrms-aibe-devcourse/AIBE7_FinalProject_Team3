package org.example.grab.domain.order.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public class LatePaymentRepository {

    private final JdbcClient jdbcClient;

    public LatePaymentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean markReconciliationRequiredIfOrderExpired(Long paymentId, OffsetDateTime now) {
        return jdbcClient.sql("""
                        UPDATE payments p
                        SET reconciliation_status = 'REQUIRED',
                            reconciliation_reason = 'PAYMENT_APPROVED_AFTER_ORDER_EXPIRED',
                            updated_at = :now
                        FROM orders o
                        WHERE p.id = :paymentId
                          AND p.order_id = o.id
                          AND o.status = 'EXPIRED'
                        """)
                .param("paymentId", paymentId)
                .param("now", now)
                .update() == 1;
    }
}
