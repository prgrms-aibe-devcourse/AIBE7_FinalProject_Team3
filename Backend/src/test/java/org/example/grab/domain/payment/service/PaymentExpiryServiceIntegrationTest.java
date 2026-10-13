package org.example.grab.domain.payment.service;

import jakarta.persistence.EntityManager;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.ShippingAddress;
import org.example.grab.domain.order.entity.StockReservation;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentExpiryServiceIntegrationTest {

    // 다른 테스트 데이터와 섞이지 않도록 과거 시각에 마감되는 주문만 다룬다.
    private static final OffsetDateTime BASE = OffsetDateTime.of(2001, 2, 3, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private PaymentExpiryService paymentExpiryService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private StockReservationRepository stockReservationRepository;

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
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded', ?) RETURNING id",
                Long.class, uniqueValue + "@example.com", "e" + uniqueValue.substring(0, 8));
        Long sellerId = jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, 'GRAB 판매자', ?) RETURNING id",
                Long.class, buyerId, "seller-" + uniqueValue + "@example.com");
        dropId = jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id) VALUES (?) RETURNING id", Long.class, sellerId);
        optionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity)
                VALUES (?, 15000, 10, 0)
                RETURNING id
                """,
                Long.class, dropId);
    }

    @Test
    @DisplayName("마감이 지난 결제 대기 주문만 만료해 선점 재고를 돌려주고, 다시 실행하면 아무것도 바꾸지 않는다")
    void expiresDueOrdersOnce() {
        // given: 마감이 지난 주문 두 건, 마감 전 주문 한 건, 결제된 주문 한 건(각 1개씩 선점)
        Long first = holdingOrderExpiringAt(BASE.plusMinutes(1));
        Long second = holdingOrderExpiringAt(BASE.plusMinutes(2));
        Long notDue = holdingOrderExpiringAt(BASE.plusMinutes(10));
        Long paid = holdingOrderExpiringAt(BASE.plusMinutes(1));
        jdbcTemplate.update("UPDATE orders SET status = 'PAID', paid_at = payment_expires_at WHERE id = ?", paid);
        OffsetDateTime now = BASE.plusMinutes(5);

        // when
        int firstRun = paymentExpiryService.expire(now);
        int secondRun = paymentExpiryService.expire(now.plusSeconds(10));
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(firstRun).isEqualTo(2);
        assertThat(secondRun).isZero();
        assertThat(status(first)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(status(second)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(status(notDue)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(status(paid)).isEqualTo(OrderStatus.PAID);
        // 네 건이 1개씩 선점했고 만료된 두 건만 돌려받았다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM drop_options WHERE id = ?", Integer.class, optionId)).isEqualTo(2);
    }

    // 결제 마감을 과거 시각으로 옮긴, 옵션 1개를 선점한 결제 대기 주문. 생성 시각도 옮겨 마감 > 생성 제약을 지킨다.
    private Long holdingOrderExpiringAt(OffsetDateTime paymentExpiresAt) {
        String uniqueValue = UUID.randomUUID().toString();
        OffsetDateTime createdExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);
        Order order = orderRepository.saveAndFlush(Order.create(
                "ORD-EXB-" + uniqueValue, buyerId, dropId, "order-key-" + uniqueValue, "a".repeat(64),
                "한정판 후드", "GRAB 판매자", 15000, 3000,
                ShippingAddress.of("홍길동", "010-1234-5678", "06236", "서울시 강남구", "101호", null),
                createdExpiresAt));
        OrderItem item = orderItemRepository.saveAndFlush(OrderItem.create(order, optionId, "옵션", 15000, 1));
        stockReservationRepository.saveAndFlush(StockReservation.hold(item, createdExpiresAt));
        jdbcTemplate.update("UPDATE drop_options SET reserved_quantity = reserved_quantity + 1 WHERE id = ?", optionId);
        jdbcTemplate.update("UPDATE orders SET created_at = ?, payment_expires_at = ? WHERE id = ?",
                paymentExpiresAt.minusMinutes(10), paymentExpiresAt, order.getId());
        entityManager.clear();
        return order.getId();
    }

    private OrderStatus status(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }
}
