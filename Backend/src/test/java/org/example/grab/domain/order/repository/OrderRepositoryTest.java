package org.example.grab.domain.order.repository;

import org.example.grab.domain.order.dto.SellerOrderListResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findsSellerOrdersWithLatestPaymentAndOptionalFilters() {
        // given
        long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, phone)
                VALUES ('seller@example.com', 'hash', '판매자', '01000000000')
                RETURNING id
                """, Long.class);
        long sellerId = jdbcTemplate.queryForObject("""
                INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
                VALUES (?, '브랜드', 'seller@example.com', 'APPROVED', ?, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, userId, userId);
        long dropId = jdbcTemplate.queryForObject("""
                INSERT INTO drops (seller_id) VALUES (?) RETURNING id
                """, Long.class, sellerId);
        UUID orderId = jdbcTemplate.queryForObject("""
                INSERT INTO orders (
                    order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
                    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
                    total_amount, recipient_name, recipient_phone, postal_code, address_line1,
                    payment_expires_at, paid_at
                ) VALUES (
                    'GR-TEST-1', ?, ?, 'key-1', 'hash', 'PAID', '상품', '브랜드',
                    1000, 2500, 3500, '구매자', '01000000001', '00000', '주소',
                    CURRENT_TIMESTAMP + INTERVAL '10 minutes', CURRENT_TIMESTAMP
                ) RETURNING public_id
                """, UUID.class, userId, dropId);
        jdbcTemplate.update("""
                INSERT INTO payments (order_id, provider, idempotency_key, amount, status, created_at)
                SELECT id, 'MOCK', 'payment-old', 3500, 'FAILED', CURRENT_TIMESTAMP - INTERVAL '1 minute'
                FROM orders WHERE public_id = ?
                """, orderId);
        jdbcTemplate.update("""
                INSERT INTO payments (order_id, provider, idempotency_key, amount, status, approved_at)
                SELECT id, 'MOCK', 'payment-new', 3500, 'SUCCEEDED', CURRENT_TIMESTAMP
                FROM orders WHERE public_id = ?
                """, orderId);

        // when
        Page<SellerOrderListResponse> filtered = orderRepository.findSellerOrders(
                "seller@example.com", dropId, "PAID", "SUCCEEDED", PageRequest.of(0, 10));
        Page<SellerOrderListResponse> unfiltered = orderRepository.findSellerOrders(
                "seller@example.com", null, null, null, PageRequest.of(0, 10));

        // then
        assertThat(filtered.getTotalElements()).isEqualTo(1);
        assertThat(filtered.getContent()).singleElement().satisfies(order -> {
            assertThat(order.orderId()).isEqualTo(orderId);
            assertThat(order.dropId()).isEqualTo(dropId);
            assertThat(order.orderStatus()).isEqualTo("PAID");
            assertThat(order.paymentStatus()).isEqualTo("SUCCEEDED");
            assertThat(order.totalAmount()).isEqualTo(3500);
        });
        assertThat(unfiltered.getContent()).singleElement()
                .extracting(SellerOrderListResponse::paymentStatus).isEqualTo("SUCCEEDED");
    }
}
