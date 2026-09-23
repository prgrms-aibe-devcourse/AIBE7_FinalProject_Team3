package org.example.grab.domain.order.repository;

import jakarta.persistence.EntityManager;
import org.example.grab.domain.order.dto.SellerOrderListProjection;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.ReservationStatus;
import org.example.grab.domain.order.entity.Shipment;
import org.example.grab.domain.order.entity.ShippingAddress;
import org.example.grab.domain.order.entity.StockReservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Transactional
@SpringBootTest
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long buyerId;
    private Long dropId;
    private Long optionId;

    @BeforeEach
    void setUp() {
        String uniqueValue = UUID.randomUUID().toString();
        buyerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname)
                VALUES (?, 'encoded-password', '구매자')
                RETURNING id
                """,
                Long.class,
                uniqueValue + "@example.com"
        );
        Long sellerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, 'GRAB 판매자', ?)
                RETURNING id
                """,
                Long.class,
                buyerId,
                "seller-" + uniqueValue + "@example.com"
        );
        dropId = jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id) VALUES (?) RETURNING id",
                Long.class,
                sellerId
        );
        optionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity)
                VALUES (?, 15000, 100)
                RETURNING id
                """,
                Long.class,
                dropId
        );
    }

    @Test
    @DisplayName("주문과 주문 항목, 재고 예약, 배송을 저장하고 조회한다")
    void saveAndFindOrderAggregate() {
        // given
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(15);
        Order order = createOrder(expiresAt);

        // when
        Order savedOrder = orderRepository.saveAndFlush(order);
        OrderItem savedItem = orderItemRepository.saveAndFlush(
                OrderItem.create(savedOrder, optionId, "검정 / L", 15000, 2)
        );
        stockReservationRepository.saveAndFlush(StockReservation.hold(savedItem, expiresAt));
        shipmentRepository.saveAndFlush(Shipment.create(savedOrder, "CJ", "1234567890"));
        entityManager.clear();

        // then
        Order foundOrder = orderRepository.findByBuyerIdAndIdempotencyKey(buyerId, "idem-key")
                .orElseThrow();
        List<OrderItem> foundItems = orderItemRepository.findAllByOrderIdOrderByIdAsc(foundOrder.getId());
        StockReservation foundReservation = stockReservationRepository
                .findByOrderItemId(foundItems.get(0).getId())
                .orElseThrow();
        Shipment foundShipment = shipmentRepository.findByOrderId(foundOrder.getId()).orElseThrow();

        assertThat(foundOrder.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(foundOrder.getProductNameSnapshot()).isEqualTo("한정판 후드");
        assertThat(foundOrder.getTotalAmount()).isEqualTo(33000);
        assertThat(foundOrder.getShippingAddress().getRecipientName()).isEqualTo("홍길동");
        assertThat(foundItems).singleElement().satisfies(item -> {
            assertThat(item.getDropId()).isEqualTo(dropId);
            assertThat(item.getOptionId()).isEqualTo(optionId);
            assertThat(item.getOptionNameSnapshot()).isEqualTo("검정 / L");
            assertThat(item.getQuantity()).isEqualTo(2);
        });
        assertThat(foundReservation.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(foundReservation.getExpiresAt()).isCloseTo(expiresAt, within(1, ChronoUnit.MICROS));
        assertThat(foundShipment.getTrackingNumber()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("주문 로그 표현에 배송지와 연락처 원문을 포함하지 않는다")
    void excludesPersonalInformationFromStringRepresentation() {
        // given
        Order order = createOrder(OffsetDateTime.now().plusMinutes(15));

        // when
        String orderLog = order.toString();
        String addressLog = order.getShippingAddress().toString();

        // then
        assertThat(orderLog)
                .doesNotContain("홍길동")
                .doesNotContain("010-1234-5678")
                .doesNotContain("서울시 강남구");
        assertThat(addressLog)
                .doesNotContain("홍길동")
                .doesNotContain("010-1234-5678")
                .doesNotContain("서울시 강남구");
    }

    @Test
    @DisplayName("판매자는 본인 DROP의 주문을 최신 결제 상태와 함께 조회한다")
    void findsSellerOrdersWithLatestPaymentAndOptionalFilters() {
        // given
        String uniqueValue = UUID.randomUUID().toString();
        String sellerEmail = "seller-" + uniqueValue + "@example.com";
        long sellerUserId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, nickname)
                VALUES (?, 'encoded-password', '판매자')
                RETURNING id
                """, Long.class, sellerEmail);
        long sellerId = jdbcTemplate.queryForObject("""
                INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
                VALUES (?, '판매자 브랜드', ?, 'APPROVED', ?, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, sellerUserId, sellerEmail, sellerUserId);
        long sellerDropId = jdbcTemplate.queryForObject("""
                INSERT INTO drops (seller_id) VALUES (?) RETURNING id
                """, Long.class, sellerId);
        UUID orderId = jdbcTemplate.queryForObject("""
                INSERT INTO orders (
                    order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
                    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
                    total_amount, recipient_name, recipient_phone, postal_code, address_line1,
                    payment_expires_at, paid_at
                ) VALUES (
                    ?, ?, ?, ?, 'hash', 'PAID', '한정 상품', '판매자 브랜드', 1000, 2500, 3500,
                    '구매자', '01000000003', '00000', '테스트 주소',
                    CURRENT_TIMESTAMP + INTERVAL '10 minutes', CURRENT_TIMESTAMP
                ) RETURNING public_id
                """, UUID.class, "GR-TEST-" + uniqueValue, sellerUserId, sellerDropId,
                "key-" + uniqueValue);
        jdbcTemplate.update("""
                INSERT INTO payments (order_id, provider, idempotency_key, amount, status, created_at)
                SELECT id, 'MOCK', ?, 3500, 'FAILED', CURRENT_TIMESTAMP - INTERVAL '1 minute'
                FROM orders WHERE public_id = ?
                """, "payment-old-" + uniqueValue, orderId);
        jdbcTemplate.update("""
                INSERT INTO payments (order_id, provider, idempotency_key, amount, status, approved_at)
                SELECT id, 'MOCK', ?, 3500, 'SUCCEEDED', CURRENT_TIMESTAMP
                FROM orders WHERE public_id = ?
                """, "payment-new-" + uniqueValue, orderId);

        // when
        Page<SellerOrderListProjection> filtered = orderRepository.findSellerOrders(
                sellerId, sellerDropId, "PAID", "SUCCEEDED", PageRequest.of(0, 10));
        Page<SellerOrderListProjection> unfiltered = orderRepository.findSellerOrders(
                sellerId, null, null, null, PageRequest.of(0, 10));

        // then
        assertThat(filtered.getTotalElements()).isEqualTo(1);
        assertThat(filtered.getContent()).singleElement().satisfies(order -> {
            assertThat(order.getOrderId()).isEqualTo(orderId);
            assertThat(order.getDropId()).isEqualTo(sellerDropId);
            assertThat(order.getOrderStatus()).isEqualTo("PAID");
            assertThat(order.getPaymentStatus()).isEqualTo("SUCCEEDED");
            assertThat(order.getTotalAmount()).isEqualTo(3500);
        });
        assertThat(unfiltered.getContent()).singleElement()
                .extracting(SellerOrderListProjection::getPaymentStatus).isEqualTo("SUCCEEDED");
    }

    private Order createOrder(OffsetDateTime expiresAt) {
        ShippingAddress address = ShippingAddress.of(
                "홍길동",
                "010-1234-5678",
                "06236",
                "서울시 강남구 테헤란로",
                "101호",
                "문 앞에 놓아주세요"
        );
        return Order.create(
                "ORD-20260923-000001",
                buyerId,
                dropId,
                "idem-key",
                "a".repeat(64),
                "한정판 후드",
                "GRAB 판매자",
                30000,
                3000,
                address,
                expiresAt
        );
    }
}
