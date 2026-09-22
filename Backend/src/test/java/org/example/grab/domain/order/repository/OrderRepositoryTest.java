package org.example.grab.domain.order.repository;

import jakarta.persistence.EntityManager;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.ReservationStatus;
import org.example.grab.domain.order.entity.Shipment;
import org.example.grab.domain.order.entity.StockReservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
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

    @Test
    @DisplayName("주문과 주문 항목, 재고 예약, 배송 정보를 저장하고 조회한다")
    void savesAndLoadsOrderAggregate() {
        // given
        PrerequisiteIds ids = createPrerequisites();
        String suffix = UUID.randomUUID().toString();
        Order order = Order.create(
                "ORD-20260922-" + suffix.substring(0, 16),
                ids.buyerId(),
                ids.dropId(),
                "order-" + suffix,
                "a".repeat(64),
                "한정판 상품",
                "GRAB 브랜드",
                25_800L,
                3_000L,
                "홍길동",
                "01012345678",
                "06236",
                "서울특별시 강남구 테헤란로 1",
                "101호",
                "문 앞에 놓아주세요",
                OffsetDateTime.now().plusMinutes(10)
        );
        order.addItem(OrderItem.create(ids.dropId(), ids.optionId(), "블랙 / M", 12_900L, 2));

        // when
        Order savedOrder = orderRepository.saveAndFlush(order);
        OrderItem savedItem = savedOrder.getItems().get(0);
        StockReservation savedReservation = stockReservationRepository.saveAndFlush(
                StockReservation.hold(savedItem, savedOrder.getPaymentExpiresAt())
        );
        Shipment savedShipment = shipmentRepository.saveAndFlush(
                Shipment.create(
                        savedOrder,
                        UUID.randomUUID().toString(),
                        "b".repeat(64),
                        "CJ",
                        "123456789012",
                        OffsetDateTime.now()
                )
        );
        entityManager.clear();

        // then
        Order foundOrder = orderRepository.findByBuyerIdAndIdempotencyKey(
                ids.buyerId(),
                "order-" + suffix
        ).orElseThrow();
        assertThat(foundOrder.getOrderNumber()).isEqualTo(savedOrder.getOrderNumber());
        assertThat(foundOrder.getProductNameSnapshot()).isEqualTo("한정판 상품");
        assertThat(orderItemRepository.findAllByOrderIdOrderByIdAsc(foundOrder.getId()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getOptionId()).isEqualTo(ids.optionId());
                    assertThat(item.getOptionNameSnapshot()).isEqualTo("블랙 / M");
                });
        assertThat(stockReservationRepository.findByOrderItemId(savedItem.getId()))
                .get()
                .extracting(StockReservation::getStatus)
                .isEqualTo(ReservationStatus.HELD);
        assertThat(shipmentRepository.findByOrderId(foundOrder.getId()))
                .get()
                .extracting(Shipment::getTrackingNumber)
                .isEqualTo(savedShipment.getTrackingNumber());
    }

    private PrerequisiteIds createPrerequisites() {
        String suffix = UUID.randomUUID().toString();
        Long buyerId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, phone, role, status, provider)
                VALUES (?, ?, ?, ?, 'USER', 'ACTIVE', 'LOCAL')
                RETURNING id
                """, Long.class, "buyer-" + suffix + "@example.com", "encoded-password", "구매자", "01000000000");
        Long sellerUserId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, display_name, phone, role, status, provider)
                VALUES (?, ?, ?, ?, 'USER', 'ACTIVE', 'LOCAL')
                RETURNING id
                """, Long.class, "seller-" + suffix + "@example.com", "encoded-password", "판매자", "01000000001");
        Long sellerId = jdbcTemplate.queryForObject("""
                INSERT INTO sellers (user_id, brand_name, contact_email, status)
                VALUES (?, 'GRAB 브랜드', ?, 'PENDING')
                RETURNING id
                """, Long.class, sellerUserId, "seller-" + suffix + "@example.com");
        Long dropId = jdbcTemplate.queryForObject("""
                INSERT INTO drops (seller_id, status)
                VALUES (?, 'DRAFT')
                RETURNING id
                """, Long.class, sellerId);
        Long optionId = jdbcTemplate.queryForObject("""
                INSERT INTO drop_options (drop_id, unit_price, total_quantity)
                VALUES (?, 12900, 10)
                RETURNING id
                """, Long.class, dropId);
        return new PrerequisiteIds(buyerId, dropId, optionId);
    }

    private record PrerequisiteIds(Long buyerId, Long dropId, Long optionId) {
    }
}
