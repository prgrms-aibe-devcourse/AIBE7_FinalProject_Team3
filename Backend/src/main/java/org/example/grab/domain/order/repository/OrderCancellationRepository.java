package org.example.grab.domain.order.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public class OrderCancellationRepository {

    private final JdbcClient jdbcClient;

    public OrderCancellationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public CancellationRequest findRequest(Long buyerId, String idempotencyKey) {
        return jdbcClient.sql("""
                        SELECT order_id, request_hash
                        FROM order_cancellation_requests
                        WHERE buyer_id = :buyerId AND idempotency_key = :idempotencyKey
                        """)
                .param("buyerId", buyerId)
                .param("idempotencyKey", idempotencyKey)
                .query((rs, rowNum) -> new CancellationRequest(
                        rs.getLong("order_id"),
                        rs.getString("request_hash")
                ))
                .optional()
                .orElse(null);
    }

    public void releaseReservations(Long orderId, OffsetDateTime now) {
        var reservations = jdbcClient.sql("""
                        SELECT r.id, i.option_id, i.quantity, r.status
                        FROM stock_reservations r
                        JOIN order_items i ON i.id = r.order_item_id
                        WHERE i.order_id = :orderId AND r.status IN ('HELD', 'COMMITTED')
                        ORDER BY i.option_id
                        FOR UPDATE OF r
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Reservation(
                        rs.getLong("id"), rs.getLong("option_id"),
                        rs.getInt("quantity"), rs.getString("status")
                )).list();
        for (Reservation reservation : reservations) {
            String quantityColumn = "HELD".equals(reservation.status())
                    ? "reserved_quantity"
                    : "sold_quantity";
            int updated = jdbcClient.sql("UPDATE drop_options SET " + quantityColumn + " = "
                            + quantityColumn + " - :quantity, updated_at = :now WHERE id = :optionId AND "
                            + quantityColumn + " >= :quantity")
                    .param("quantity", reservation.quantity())
                    .param("now", now)
                    .param("optionId", reservation.optionId())
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("취소 주문의 재고 반환에 실패했습니다.");
            }
            jdbcClient.sql("""
                            UPDATE stock_reservations
                            SET status = 'RELEASED', released_at = :now,
                                release_reason = 'ORDER_CANCELED', release_destination = 'AVAILABLE',
                                updated_at = :now
                            WHERE id = :reservationId AND status = :status
                            """)
                    .param("now", now)
                    .param("reservationId", reservation.id())
                    .param("status", reservation.status())
                    .update();
        }
    }

    public void cancelMockPayment(Long orderId, Long buyerId, String idempotencyKey,
                                  String requestHash, String reason, OffsetDateTime now) {
        Payment payment = jdbcClient.sql("""
                        SELECT id, provider, amount
                        FROM payments
                        WHERE order_id = :orderId AND status = 'SUCCEEDED'
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        FOR UPDATE
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Payment(
                        rs.getLong("id"), rs.getString("provider"), rs.getLong("amount")
                )).optional()
                .orElse(null);
        if (payment == null || !"MOCK".equals(payment.provider())) {
            throw new org.example.grab.global.error.BusinessException(
                    org.example.grab.global.error.ErrorCode.PAYMENT_CANCEL_FAILED
            );
        }
        jdbcClient.sql("""
                        INSERT INTO payment_cancellations (
                            payment_id, requested_by, purpose, idempotency_key, request_hash,
                            amount, reason, status, completed_at, attempt_count
                        ) VALUES (:paymentId, :buyerId, 'ORDER_CANCEL', :idempotencyKey, :requestHash,
                                  :amount, :reason, 'SUCCEEDED', :now, 1)
                        """)
                .param("paymentId", payment.id())
                .param("buyerId", buyerId)
                .param("idempotencyKey", idempotencyKey)
                .param("requestHash", requestHash)
                .param("amount", payment.amount())
                .param("reason", reason)
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        UPDATE payments
                        SET status = 'CANCELED', canceled_at = :now, updated_at = :now
                        WHERE id = :paymentId AND status = 'SUCCEEDED'
                        """)
                .param("now", now)
                .param("paymentId", payment.id())
                .update();
    }

    public void saveRequest(Long orderId, Long buyerId, String key, String hash, String reason) {
        jdbcClient.sql("""
                        INSERT INTO order_cancellation_requests (
                            order_id, buyer_id, idempotency_key, request_hash, reason
                        ) VALUES (:orderId, :buyerId, :key, :hash, :reason)
                        """)
                .param("orderId", orderId).param("buyerId", buyerId)
                .param("key", key).param("hash", hash).param("reason", reason)
                .update();
    }

    public record CancellationRequest(Long orderId, String requestHash) {
    }

    private record Reservation(Long id, Long optionId, int quantity, String status) {
    }

    private record Payment(Long id, String provider, long amount) {
    }
}
