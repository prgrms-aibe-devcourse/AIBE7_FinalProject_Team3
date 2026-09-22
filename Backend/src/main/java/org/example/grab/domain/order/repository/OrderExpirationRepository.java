package org.example.grab.domain.order.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class OrderExpirationRepository {

    private final JdbcClient jdbcClient;

    public OrderExpirationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Long> lockExpiredOrderIds(OffsetDateTime now, int batchSize) {
        return jdbcClient.sql("""
                        SELECT id
                        FROM orders
                        WHERE status = 'PAYMENT_PENDING'
                          AND payment_expires_at <= :now
                        ORDER BY payment_expires_at, id
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("now", now)
                .param("batchSize", batchSize)
                .query(Long.class)
                .list();
    }

    public void expire(Long orderId, OffsetDateTime now) {
        List<HeldReservation> reservations = jdbcClient.sql("""
                        SELECT r.id AS reservation_id, i.option_id, i.quantity
                        FROM stock_reservations r
                        JOIN order_items i ON i.id = r.order_item_id
                        WHERE i.order_id = :orderId
                          AND r.status = 'HELD'
                        ORDER BY i.option_id
                        FOR UPDATE OF r
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new HeldReservation(
                        rs.getLong("reservation_id"),
                        rs.getLong("option_id"),
                        rs.getInt("quantity")
                ))
                .list();

        for (HeldReservation reservation : reservations) {
            int stockUpdated = jdbcClient.sql("""
                            UPDATE drop_options
                            SET reserved_quantity = reserved_quantity - :quantity,
                                updated_at = :now
                            WHERE id = :optionId
                              AND reserved_quantity >= :quantity
                            """)
                    .param("quantity", reservation.quantity())
                    .param("now", now)
                    .param("optionId", reservation.optionId())
                    .update();
            if (stockUpdated != 1) {
                throw new IllegalStateException("만료 주문의 예약 재고 복구에 실패했습니다.");
            }
            jdbcClient.sql("""
                            UPDATE stock_reservations
                            SET status = 'RELEASED', released_at = :now,
                                release_reason = 'EXPIRED', release_destination = 'AVAILABLE',
                                updated_at = :now
                            WHERE id = :reservationId AND status = 'HELD'
                            """)
                    .param("now", now)
                    .param("reservationId", reservation.reservationId())
                    .update();
        }

        jdbcClient.sql("""
                        UPDATE orders
                        SET status = 'EXPIRED', updated_at = :now
                        WHERE id = :orderId AND status = 'PAYMENT_PENDING'
                        """)
                .param("now", now)
                .param("orderId", orderId)
                .update();
    }

    private record HeldReservation(Long reservationId, Long optionId, int quantity) {
    }
}
