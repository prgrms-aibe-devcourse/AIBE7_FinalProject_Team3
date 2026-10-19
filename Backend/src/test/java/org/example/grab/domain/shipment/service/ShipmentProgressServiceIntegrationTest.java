package org.example.grab.domain.shipment.service;

import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.shipment.repository.ShipmentRepository;
import org.example.grab.global.config.JpaConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({JpaConfig.class, ShipmentProgressService.class, ShipmentProgressBatchService.class})
@Testcontainers
class ShipmentProgressServiceIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("grab.mock-delivery.stage-interval", () -> "2m");
    }

    @Autowired
    private ShipmentProgressService shipmentProgressService;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long dropId;

    @BeforeEach
    void setUp() {
        String uniqueValue = UUID.randomUUID().toString();
        Long buyerId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded-password', '구매자') RETURNING id",
                Long.class, "buyer-" + uniqueValue + "@example.com");
        Long sellerUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded-password', '판매자') RETURNING id",
                Long.class, "seller-" + uniqueValue + "@example.com");
        Long sellerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, 'GRAB 판매자', ?) RETURNING id
                """,
                Long.class, sellerUserId, "contact-" + uniqueValue + "@example.com");
        Long categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '패션') RETURNING id",
                Long.class, "TEST-" + uniqueValue);
        dropId = jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description,
                                   shipping_fee, shipping_notice, sale_starts_at, sale_ends_at,
                                   published_at, grab_started_at)
                VALUES (?, ?, 'GRAB', '테스트 상품', '설명', 3000, '안내',
                        CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP - INTERVAL '1 hour')
                RETURNING id
                """,
                Long.class, sellerId, categoryId);
        this.buyerId = buyerId;
    }

    private Long buyerId;

    @Test
    @DisplayName("단계 간격이 지난 SHIPPED 주문은 다음 단계로 한 번만 전진한다")
    void advancesOneStagePerRun() {
        // given
        Long orderId = insertShippedOrder();
        insertShipment(orderId, "COLLECTED", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(3));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        shipmentProgressService.progress(now);

        // then
        assertThat(shipmentRow(orderId).get("delivery_stage")).isEqualTo("LINE_HAUL_OUT");
    }

    @Test
    @DisplayName("단계 간격이 지나지 않았으면 전진하지 않는다")
    void doesNotAdvanceBeforeInterval() {
        // given
        Long orderId = insertShippedOrder();
        insertShipment(orderId, "COLLECTED", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        shipmentProgressService.progress(now);

        // then
        assertThat(shipmentRow(orderId).get("delivery_stage")).isEqualTo("COLLECTED");
    }

    @Test
    @DisplayName("마지막 단계에서 간격이 지나면 주문이 DELIVERED가 되고 배송완료 시각이 채워진다")
    void completesDeliveryAfterLastStage() {
        // given
        Long orderId = insertShippedOrder();
        insertShipment(orderId, "OUT_FOR_DELIVERY", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(3));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        shipmentProgressService.progress(now);

        // then
        assertThat(orderRow(orderId).get("status")).isEqualTo("DELIVERED");
        assertThat(shipmentRow(orderId).get("delivered_at")).isNotNull();
    }

    @Test
    @DisplayName("SHIPPED가 아닌 주문은 간격이 지나도 전진하지 않는다")
    void ignoresOrdersNotShipped() {
        // given: 이미 DELIVERED인 주문
        Long orderId = insertShippedOrder();
        insertShipment(orderId, "OUT_FOR_DELIVERY", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(3));
        jdbcTemplate.update("UPDATE orders SET status = 'DELIVERED' WHERE id = ?", orderId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        long processed = shipmentProgressService.progress(now);

        // then
        assertThat(processed).isZero();
        assertThat(shipmentRow(orderId).get("delivery_stage")).isEqualTo("OUT_FOR_DELIVERY");
    }

    private Long insertShippedOrder() {
        String uniqueValue = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO orders (order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
                                     product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
                                     total_amount, recipient_name, recipient_phone, postal_code, address_line1,
                                     payment_expires_at, paid_at)
                VALUES (?, ?, ?, ?, ?, 'SHIPPED', '테스트 상품', 'GRAB 판매자', 30000, 3000, 33000,
                        '받는 사람', '01000000000', '00000', '주소',
                        CURRENT_TIMESTAMP + INTERVAL '15 minutes', CURRENT_TIMESTAMP - INTERVAL '2 hours')
                RETURNING id
                """,
                Long.class, "GR-" + uniqueValue, buyerId, dropId, "idem-" + uniqueValue, "a".repeat(64));
    }

    private void insertShipment(Long orderId, String deliveryStage, OffsetDateTime stageUpdatedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO shipments (order_id, carrier_code, tracking_number, idempotency_key, request_hash,
                                        shipped_at, delivery_stage, stage_updated_at)
                VALUES (?, 'CJ대한통운', '123456789012', ?, ?, CURRENT_TIMESTAMP - INTERVAL '2 hours', ?, ?)
                """,
                orderId, "ship-" + UUID.randomUUID(), "b".repeat(64), deliveryStage, stageUpdatedAt);
    }

    private Map<String, Object> shipmentRow(Long orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM shipments WHERE order_id = ?", orderId);
    }

    private Map<String, Object> orderRow(Long orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
    }
}
