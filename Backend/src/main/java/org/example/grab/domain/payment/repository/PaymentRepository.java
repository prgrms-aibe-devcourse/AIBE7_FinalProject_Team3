package org.example.grab.domain.payment.repository;

import org.example.grab.domain.payment.dto.PaymentHistoryResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class PaymentRepository {

    private final JdbcClient jdbcClient;

    public PaymentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void lockIdempotencyKey(String key) {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtext(:key))")
                .param("key", key)
                .query((rs, rowNum) -> true)
                .single();
    }

    public PaymentOrder lockOrder(Long orderId) {
        return jdbcClient.sql("""
                        SELECT id, order_number, buyer_id, status, total_amount, payment_expires_at
                        FROM orders WHERE id = :orderId FOR UPDATE
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new PaymentOrder(
                        rs.getLong("id"), rs.getString("order_number"), rs.getLong("buyer_id"),
                        rs.getString("status"), rs.getLong("total_amount"),
                        rs.getObject("payment_expires_at", OffsetDateTime.class)
                )).optional().orElse(null);
    }

    public StoredPayment findByIdempotencyKey(String key) {
        return jdbcClient.sql("""
                        SELECT p.id, p.order_id, o.order_number, p.amount, p.status,
                               p.request_hash, p.approved_at
                        FROM payments p JOIN orders o ON o.id = p.order_id
                        WHERE p.provider = 'MOCK' AND p.idempotency_key = :key
                        """)
                .param("key", key)
                .query((rs, rowNum) -> new StoredPayment(
                        rs.getLong("id"), rs.getLong("order_id"), rs.getString("order_number"),
                        rs.getLong("amount"), rs.getString("status"), rs.getString("request_hash"),
                        rs.getObject("approved_at", OffsetDateTime.class)
                )).optional().orElse(null);
    }

    public Long createPayment(Long orderId, String key, String hash, long amount,
                              String status, String providerPaymentId,
                              String reconciliationStatus, String failureCode,
                              String failureMessage, OffsetDateTime approvedAt) {
        return jdbcClient.sql("""
                        INSERT INTO payments (
                            order_id, provider, idempotency_key, request_hash, provider_payment_id,
                            amount, status, reconciliation_status, failure_code, failure_message, approved_at
                        ) VALUES (:orderId, 'MOCK', :key, :hash, :providerPaymentId,
                                  :amount, :status, :reconciliationStatus,
                                  :failureCode, :failureMessage, :approvedAt)
                        RETURNING id
                        """)
                .param("orderId", orderId).param("key", key).param("hash", hash)
                .param("providerPaymentId", providerPaymentId).param("amount", amount)
                .param("status", status).param("reconciliationStatus", reconciliationStatus)
                .param("failureCode", failureCode, java.sql.Types.VARCHAR)
                .param("failureMessage", failureMessage, java.sql.Types.VARCHAR)
                .param("approvedAt", approvedAt, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Long.class).single();
    }

    public void commitReservations(Long orderId, OffsetDateTime now) {
        List<Reservation> reservations = jdbcClient.sql("""
                        SELECT r.id, i.option_id, i.quantity
                        FROM stock_reservations r
                        JOIN order_items i ON i.id = r.order_item_id
                        WHERE i.order_id = :orderId AND r.status = 'HELD'
                        ORDER BY i.option_id
                        FOR UPDATE OF r
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Reservation(
                        rs.getLong("id"), rs.getLong("option_id"), rs.getInt("quantity")
                )).list();
        if (reservations.isEmpty()) {
            throw new IllegalStateException("확정할 재고 예약이 없습니다.");
        }
        List<Long> optionIds = reservations.stream().map(Reservation::optionId).toList();
        jdbcClient.sql("SELECT id FROM drop_options WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", optionIds).query(Long.class).list();
        for (Reservation reservation : reservations) {
            int updated = jdbcClient.sql("""
                            UPDATE drop_options
                            SET reserved_quantity = reserved_quantity - :quantity,
                                sold_quantity = sold_quantity + :quantity,
                                updated_at = :now
                            WHERE id = :optionId AND reserved_quantity >= :quantity
                            """)
                    .param("quantity", reservation.quantity()).param("now", now)
                    .param("optionId", reservation.optionId()).update();
            if (updated != 1) {
                throw new IllegalStateException("결제 재고 확정에 실패했습니다.");
            }
            jdbcClient.sql("""
                            UPDATE stock_reservations
                            SET status = 'COMMITTED', committed_at = :now, updated_at = :now
                            WHERE id = :reservationId AND status = 'HELD'
                            """)
                    .param("now", now).param("reservationId", reservation.id()).update();
        }
    }

    public void markOrderPaid(Long orderId, OffsetDateTime now) {
        jdbcClient.sql("""
                        UPDATE orders SET status = 'PAID', paid_at = :now, updated_at = :now
                        WHERE id = :orderId AND status = 'PAYMENT_PENDING'
                        """)
                .param("now", now).param("orderId", orderId).update();
    }

    public void saveEvent(Long paymentId, String eventKey, String eventType,
                          String result, String payload, OffsetDateTime now) {
        jdbcClient.sql("""
                        INSERT INTO payment_events (
                            payment_id, event_key, event_type, source, processing_result, payload, occurred_at
                        ) VALUES (:paymentId, :eventKey, :eventType, 'API', :result,
                                  CAST(:payload AS jsonb), :now)
                        """)
                .param("paymentId", paymentId).param("eventKey", eventKey)
                .param("eventType", eventType).param("result", result)
                .param("payload", payload).param("now", now).update();
    }

    public boolean canAccess(Long userId, Long orderId) {
        return jdbcClient.sql("""
                        SELECT 1 FROM orders o
                        JOIN drops d ON d.id = o.drop_id
                        JOIN sellers s ON s.id = d.seller_id
                        WHERE o.id = :orderId AND (o.buyer_id = :userId OR s.user_id = :userId)
                        """)
                .param("orderId", orderId).param("userId", userId)
                .query(Integer.class).optional().isPresent();
    }

    public List<PaymentHistoryResponse> findHistory(Long orderId) {
        return jdbcClient.sql("""
                        SELECT id, order_id, amount, status, reconciliation_status, approved_at, created_at
                        FROM payments WHERE order_id = :orderId ORDER BY created_at DESC, id DESC
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new PaymentHistoryResponse(
                        rs.getLong("id"), rs.getLong("order_id"), "MOCK_CARD",
                        rs.getLong("amount"), rs.getString("status"),
                        rs.getString("reconciliation_status"),
                        rs.getObject("approved_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class)
                )).list();
    }

    public record PaymentOrder(Long id, String orderNumber, Long buyerId, String status,
                               long amount, OffsetDateTime expiresAt) {
    }

    public record StoredPayment(Long id, Long orderId, String orderNumber, long amount,
                                String status, String requestHash, OffsetDateTime approvedAt) {
    }

    private record Reservation(Long id, Long optionId, int quantity) {
    }
}
