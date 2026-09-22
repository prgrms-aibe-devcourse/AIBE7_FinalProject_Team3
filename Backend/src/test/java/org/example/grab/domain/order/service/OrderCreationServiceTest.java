package org.example.grab.domain.order.service;

import jakarta.persistence.EntityManager;
import org.example.grab.domain.order.dto.OrderCancelRequest;
import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.dto.OrderCreateResponse;
import org.example.grab.domain.order.dto.ShipmentCreateRequest;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class OrderCreationServiceTest {

    @Autowired
    private OrderCreationService orderCreationService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderCancellationService orderCancellationService;

    @Autowired
    private OrderExpirationService orderExpirationService;

    @Autowired
    private SellerOrderService sellerOrderService;

    @Autowired
    private OrderQueryService orderQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("주문 생성 시 재고를 선점하고 동일 멱등 요청에는 기존 주문을 반환한다")
    void createsOrderAndReturnsExistingOrderForSameRequest() {
        // given
        Fixture fixture = createFixture(5);
        String idempotencyKey = UUID.randomUUID().toString();
        OrderCreateRequest request = createRequest(fixture.dropId(), fixture.optionId(), 2);

        // when
        OrderCreateResponse first = orderCreationService.create(fixture.buyerId(), idempotencyKey, request);
        OrderCreateResponse second = orderCreationService.create(fixture.buyerId(), idempotencyKey, request);

        // then
        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(first.itemsAmount()).isEqualTo(20_000L);
        assertThat(first.shippingFee()).isEqualTo(3_000L);
        assertThat(first.totalAmount()).isEqualTo(23_000L);
        assertThat(orderRepository.count()).isEqualTo(1L);
        assertThat(queryReservedQuantity(fixture.optionId())).isEqualTo(2);
        assertThat(queryReservationCount(first.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 멱등 키에 다른 요청 본문을 사용하면 충돌로 거부한다")
    void rejectsDifferentRequestForSameIdempotencyKey() {
        // given
        Fixture fixture = createFixture(5);
        String idempotencyKey = UUID.randomUUID().toString();
        orderCreationService.create(
                fixture.buyerId(),
                idempotencyKey,
                createRequest(fixture.dropId(), fixture.optionId(), 1)
        );

        // when, then
        assertThatThrownBy(() -> orderCreationService.create(
                fixture.buyerId(),
                idempotencyKey,
                createRequest(fixture.dropId(), fixture.optionId(), 2)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("옵션 하나라도 재고가 부족하면 주문과 재고 변경을 모두 롤백한다")
    void rollsBackWhenStockIsInsufficient() {
        // given
        Fixture fixture = createFixture(1);
        OrderCreateRequest request = createRequest(fixture.dropId(), fixture.optionId(), 2);

        // when, then
        assertThatThrownBy(() -> orderCreationService.create(
                fixture.buyerId(),
                UUID.randomUUID().toString(),
                request
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(orderRepository.count()).isZero();
        assertThat(queryReservedQuantity(fixture.optionId())).isZero();
    }

    @Test
    @DisplayName("결제 대기 주문을 취소하면 예약 재고를 한 번만 반환한다")
    void cancelsPaymentPendingOrderOnce() {
        // given
        Fixture fixture = createFixture(5);
        OrderCreateResponse created = orderCreationService.create(
                fixture.buyerId(), UUID.randomUUID().toString(),
                createRequest(fixture.dropId(), fixture.optionId(), 2)
        );
        String cancellationKey = UUID.randomUUID().toString();
        OrderCancelRequest request = new OrderCancelRequest("단순 변심");

        // when
        var first = orderCancellationService.cancel(
                fixture.buyerId(), created.orderId(), cancellationKey, request
        );
        var second = orderCancellationService.cancel(
                fixture.buyerId(), created.orderId(), cancellationKey, request
        );

        // then
        assertThat(first.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(second.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(queryReservedQuantity(fixture.optionId())).isZero();
        assertThat(queryReservationStatus(created.orderId())).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("결제 마감이 지난 주문을 반복 처리해도 재고는 한 번만 반환된다")
    void expiresOrderOnce() {
        // given
        Fixture fixture = createFixture(5);
        OrderCreateResponse created = orderCreationService.create(
                fixture.buyerId(), UUID.randomUUID().toString(),
                createRequest(fixture.dropId(), fixture.optionId(), 2)
        );
        jdbcTemplate.update(
                """
                UPDATE orders
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '20 minutes',
                    payment_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE id = ?
                """,
                created.orderId()
        );

        // when
        int firstCount = orderExpirationService.expirePaymentPendingOrders();
        int secondCount = orderExpirationService.expirePaymentPendingOrders();

        // then
        assertThat(firstCount).isEqualTo(1);
        assertThat(secondCount).isZero();
        assertThat(queryOrderStatus(created.orderId())).isEqualTo("EXPIRED");
        assertThat(queryReservedQuantity(fixture.optionId())).isZero();
    }

    @Test
    @DisplayName("판매자는 자신의 결제 완료 주문을 배송 완료까지 전이한다")
    void transitionsSellerShipmentStates() {
        // given
        Fixture fixture = createFixture(5);
        OrderCreateResponse created = orderCreationService.create(
                fixture.buyerId(), UUID.randomUUID().toString(),
                createRequest(fixture.dropId(), fixture.optionId(), 1)
        );
        markOrderPaid(created.orderId(), fixture.optionId());
        entityManager.clear();

        var buyerDetail = orderQueryService.findMyOrder(fixture.buyerId(), created.orderId());
        var sellerOrders = sellerOrderService.findOrders(
                fixture.sellerUserId(), fixture.dropId(), OrderStatus.PAID, "SUCCEEDED", 0, 20
        );

        // when
        var preparing = sellerOrderService.prepareShipment(fixture.sellerUserId(), created.orderId());
        var shipped = sellerOrderService.ship(
                fixture.sellerUserId(), created.orderId(), UUID.randomUUID().toString(),
                new ShipmentCreateRequest("CJ대한통운", "123456789012", OffsetDateTime.now())
        );
        var delivered = sellerOrderService.completeDelivery(fixture.sellerUserId(), created.orderId());
        entityManager.flush();

        // then
        assertThat(buyerDetail.paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(buyerDetail.shipping()).isNull();
        assertThat(sellerOrders.content()).hasSize(1);
        assertThat(preparing.status()).isEqualTo(OrderStatus.PREPARING);
        assertThat(shipped.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(delivered.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivered_at IS NOT NULL FROM shipments WHERE order_id = ?",
                Boolean.class, created.orderId()
        )).isTrue();
    }

    private OrderCreateRequest createRequest(Long dropId, Long optionId, int quantity) {
        return new OrderCreateRequest(
                dropId,
                List.of(new OrderCreateRequest.OrderItemRequest(optionId, quantity)),
                new OrderCreateRequest.ShippingAddressRequest(
                        "홍길동",
                        "01012345678",
                        "06236",
                        "서울특별시 강남구 테헤란로 1",
                        "101호",
                        null
                )
        );
    }

    private Fixture createFixture(int totalQuantity) {
        String suffix = UUID.randomUUID().toString();
        Long buyerId = createUser("buyer-" + suffix + "@example.com", "구매자");
        Long sellerUserId = createUser("seller-" + suffix + "@example.com", "판매자");
        Long sellerId = jdbcTemplate.queryForObject("""
                INSERT INTO sellers (
                    user_id, brand_name, contact_email, status, reviewed_by, reviewed_at
                ) VALUES (?, 'GRAB 브랜드', ?, 'APPROVED', ?, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, sellerUserId, "seller-" + suffix + "@example.com", sellerUserId);
        Long categoryId = jdbcTemplate.queryForObject("""
                INSERT INTO categories (code, name)
                VALUES (?, '테스트 카테고리')
                RETURNING id
                """, Long.class, "category-" + suffix);
        Long dropId = jdbcTemplate.queryForObject("""
                INSERT INTO drops (
                    seller_id, category_id, status, name, description, shipping_fee,
                    shipping_notice, sale_starts_at, sale_ends_at, published_at, grab_started_at
                ) VALUES (?, ?, 'GRAB', '한정판 상품', '상품 설명', 3000,
                          '배송 안내', CURRENT_TIMESTAMP - INTERVAL '1 hour',
                          CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, sellerId, categoryId);
        Long optionId = jdbcTemplate.queryForObject("""
                INSERT INTO drop_options (drop_id, unit_price, total_quantity)
                VALUES (?, 10000, ?)
                RETURNING id
                """, Long.class, dropId, totalQuantity);
        return new Fixture(buyerId, sellerUserId, dropId, optionId);
    }

    private Long createUser(String email, String displayName) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, phone, role, status, provider)
                VALUES (?, 'encoded-password', ?, '01000000000', 'USER', 'ACTIVE', 'LOCAL')
                RETURNING id
                """, Long.class, email, displayName);
    }

    private int queryReservedQuantity(Long optionId) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM drop_options WHERE id = ?",
                Integer.class,
                optionId
        );
    }

    private int queryReservationCount(Long orderId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM stock_reservations r
                JOIN order_items i ON i.id = r.order_item_id
                WHERE i.order_id = ?
                """, Integer.class, orderId);
    }

    private String queryReservationStatus(Long orderId) {
        return jdbcTemplate.queryForObject("""
                SELECT r.status FROM stock_reservations r
                JOIN order_items i ON i.id = r.order_item_id
                WHERE i.order_id = ?
                """, String.class, orderId);
    }

    private String queryOrderStatus(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId
        );
    }

    private void markOrderPaid(Long orderId, Long optionId) {
        jdbcTemplate.update("""
                UPDATE orders SET status = 'PAID', paid_at = CURRENT_TIMESTAMP WHERE id = ?
                """, orderId);
        jdbcTemplate.update("""
                UPDATE stock_reservations SET status = 'COMMITTED', committed_at = CURRENT_TIMESTAMP
                WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id = ?)
                """, orderId);
        jdbcTemplate.update("""
                UPDATE drop_options SET reserved_quantity = 0, sold_quantity = 1 WHERE id = ?
                """, optionId);
        jdbcTemplate.update("""
                INSERT INTO payments (
                    order_id, provider, idempotency_key, request_hash, provider_payment_id,
                    amount, status, reconciliation_status, approved_at
                ) VALUES (?, 'MOCK', ?, ?, ?, 13000, 'SUCCEEDED', 'NONE', CURRENT_TIMESTAMP)
                """, orderId, UUID.randomUUID().toString(), "c".repeat(64),
                "payment-" + UUID.randomUUID());
    }

    private record Fixture(Long buyerId, Long sellerUserId, Long dropId, Long optionId) {
    }
}
