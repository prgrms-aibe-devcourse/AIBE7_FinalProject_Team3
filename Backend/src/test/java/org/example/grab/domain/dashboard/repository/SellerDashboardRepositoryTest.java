package org.example.grab.domain.dashboard.repository;

import org.example.grab.domain.dashboard.dto.SellerDashboardSummaryResponse.StockSummary;
import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropProjection;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(SellerDashboardRepository.class)
@Testcontainers
class SellerDashboardRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private SellerDashboardRepository sellerDashboardRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sellerId;
    private Long otherSellerId;
    private Long categoryId;
    private Long buyerId;
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        sellerId = insertSeller();
        otherSellerId = insertSeller();
        buyerId = insertUser();
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '테스트') RETURNING id",
                Long.class,
                "TEST-" + UUID.randomUUID());
    }

    @Test
    @DisplayName("시작 임박 조회는 본인 WISH만 시간 범위와 임박 시각 순으로 반환한다")
    void findUpcomingStarts_filtersOwnerStatusAndTime() {
        // given
        insertDrop(sellerId, "WISH", "가까운 시작", now.plusMinutes(30), now.plusHours(3));
        insertDrop(sellerId, "WISH", "기간 끝 시작", now.plusHours(1), now.plusHours(4));
        insertDrop(sellerId, "WISH", "이미 시작", now, now.plusHours(2));
        insertDrop(sellerId, "WISH", "기간 밖 시작", now.plusMinutes(61), now.plusHours(3));
        insertDrop(sellerId, "DRAFT", "임시 저장", now.plusMinutes(20), now.plusHours(2));
        insertDrop(otherSellerId, "WISH", "다른 판매자", now.plusMinutes(10), now.plusHours(2));

        // when
        List<UpcomingDropProjection> result = sellerDashboardRepository.findUpcomingDrops(
                sellerId, UpcomingDropEventType.START, now, now.plusHours(1));

        // then
        assertThat(result).extracting(UpcomingDropProjection::name)
                .containsExactly("가까운 시작", "기간 끝 시작");
        assertThat(result).extracting(UpcomingDropProjection::status).containsOnly("WISH");
    }

    @Test
    @DisplayName("종료 임박 조회는 본인 GRAB만 시간 범위와 임박 시각 순으로 반환한다")
    void findUpcomingEnds_filtersOwnerStatusAndTime() {
        // given
        insertDrop(sellerId, "GRAB", "가까운 종료", now.minusHours(2), now.plusMinutes(30));
        insertDrop(sellerId, "GRAB", "기간 끝 종료", now.minusHours(2), now.plusHours(1));
        insertDrop(sellerId, "GRAB", "이미 종료", now.minusHours(2), now);
        insertDrop(sellerId, "GRAB", "기간 밖 종료", now.minusHours(2), now.plusMinutes(61));
        insertDrop(sellerId, "WISH", "판매 대기", now.minusHours(2), now.plusMinutes(20));
        insertDrop(otherSellerId, "GRAB", "다른 판매자", now.minusHours(2), now.plusMinutes(10));

        // when
        List<UpcomingDropProjection> result = sellerDashboardRepository.findUpcomingDrops(
                sellerId, UpcomingDropEventType.END, now, now.plusHours(1));

        // then
        assertThat(result).extracting(UpcomingDropProjection::name)
                .containsExactly("가까운 종료", "기간 끝 종료");
        assertThat(result).extracting(UpcomingDropProjection::status).containsOnly("GRAB");
    }

    @Test
    @DisplayName("DROP 상태별 집계는 본인 DROP만 센다")
    void countDropsByStatus_countsOwnDropsOnly() {
        // given
        insertDrop(sellerId, "WISH", "대기1", now.plusHours(1), now.plusHours(2));
        insertDrop(sellerId, "WISH", "대기2", now.plusHours(1), now.plusHours(2));
        insertDrop(sellerId, "GRAB", "판매중", now.minusHours(1), now.plusHours(2));
        insertDrop(otherSellerId, "WISH", "다른 판매자", now.plusHours(1), now.plusHours(2));

        // when
        Map<String, Long> counts = sellerDashboardRepository.countDropsByStatus(sellerId);

        // then
        assertThat(counts).containsOnly(entry("WISH", 2L), entry("GRAB", 1L));
    }

    @Test
    @DisplayName("주문 상태별 집계는 본인 주문만 주문 생성 시각 기준으로 센다")
    void countOrdersByStatus_filtersOwnerAndPeriod() {
        // given
        Long dropId = insertDrop(sellerId, "GRAB", "판매중", now.minusDays(10), now.plusDays(10));
        Long otherDropId = insertDrop(
                otherSellerId, "GRAB", "다른 판매자", now.minusDays(10), now.plusDays(10));
        insertOrder(dropId, "PAYMENT_PENDING", now);
        insertOrder(dropId, "PAID", now.plusDays(1));
        insertOrder(dropId, "PAID", now.minusDays(1));
        insertOrder(otherDropId, "PAID", now);

        // when
        Map<String, Long> all = sellerDashboardRepository.countOrdersByStatus(sellerId, null, null);
        Map<String, Long> ranged = sellerDashboardRepository.countOrdersByStatus(sellerId, now, now.plusDays(1));

        // then
        assertThat(all).containsOnly(entry("PAYMENT_PENDING", 1L), entry("PAID", 2L));
        assertThat(ranged).containsOnly(entry("PAYMENT_PENDING", 1L), entry("PAID", 1L));
    }

    @Test
    @DisplayName("결제 집계는 시도 건수를 세고, 보정 필요 건수는 기간과 무관하게 센다")
    void countPayments_countsAttemptsAndReconciliation() {
        // given
        Long dropId = insertDrop(sellerId, "GRAB", "판매중", now.minusDays(10), now.plusDays(10));
        Long otherDropId = insertDrop(
                otherSellerId, "GRAB", "다른 판매자", now.minusDays(10), now.plusDays(10));
        Long orderId = insertOrder(dropId, "PAID", now);
        insertPayment(orderId, "FAILED", "NONE", now.minusDays(5));
        insertPayment(orderId, "FAILED", "NONE", now);
        insertPayment(orderId, "SUCCEEDED", "REQUIRED", now);
        insertPayment(insertOrder(otherDropId, "PAID", now), "SUCCEEDED", "NONE", now);

        // when
        Map<String, Long> ranged = sellerDashboardRepository.countPaymentsByStatus(
                sellerId, now.minusDays(1), now.plusDays(1));
        long reconciliationRequired = sellerDashboardRepository.countReconciliationRequired(sellerId);

        // then
        assertThat(ranged).containsOnly(entry("FAILED", 1L), entry("SUCCEEDED", 1L));
        assertThat(reconciliationRequired).isEqualTo(1L);
    }

    @Test
    @DisplayName("재고 합계는 취소 DROP을 빼고 비활성 옵션은 포함해 계산식대로 센다")
    void sumStock_excludesCanceledDropAndIncludesInactiveOption() {
        // given
        Long dropId = insertDrop(sellerId, "GRAB", "판매중", now.minusHours(1), now.plusHours(2));
        insertOption(dropId, 100, 10, 20, 5, true);
        insertOption(dropId, 50, 0, 0, 0, false);
        Long canceledDropId = insertDrop(sellerId, "CANCELED", "취소", now.plusHours(1), now.plusHours(2));
        insertOption(canceledDropId, 999, 0, 0, 0, true);
        Long otherDropId = insertDrop(
                otherSellerId, "GRAB", "다른 판매자", now.minusHours(1), now.plusHours(2));
        insertOption(otherDropId, 777, 0, 0, 0, true);

        // when
        StockSummary summary = sellerDashboardRepository.sumStock(sellerId);

        // then
        assertThat(summary).isEqualTo(new StockSummary(115L, 10L, 20L));
    }

    private Long insertUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id",
                Long.class,
                "user-" + suffix + "@example.com",
                "회원-" + suffix);
    }

    private Long insertSeller() {
        String suffix = UUID.randomUUID().toString();
        Long userId = insertUser();
        return jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?) RETURNING id",
                Long.class,
                userId,
                "contact-" + suffix + "@example.com");
    }

    private Long insertDrop(
            Long dropSellerId, String status, String name, OffsetDateTime saleStartsAt, OffsetDateTime saleEndsAt) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee,
                                   shipping_notice, sale_starts_at, sale_ends_at, published_at, grab_started_at,
                                   closed_at, close_reason)
                VALUES (?, ?, ?, ?, '설명', 0, '안내', ?, ?, CURRENT_TIMESTAMP,
                        CASE WHEN ? IN ('GRAB', 'ENDED') THEN ? ELSE NULL END,
                        CASE WHEN ? IN ('ENDED', 'CANCELED') THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CASE WHEN ? IN ('ENDED', 'CANCELED') THEN 'SELLER_CANCELED' ELSE NULL END)
                RETURNING id
                """,
                Long.class,
                dropSellerId, categoryId, status, name, saleStartsAt, saleEndsAt,
                status, saleStartsAt, status, status);
    }

    private void insertOption(Long dropId, int total, int reserved, int sold, int withheld, boolean active) {
        jdbcTemplate.update(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity,
                                          sold_quantity, withheld_quantity, is_active, sort_order)
                VALUES (?, 1000, ?, ?, ?, ?, ?, 0)
                """,
                dropId, total, reserved, sold, withheld, active);
    }

    private Long insertOrder(Long dropId, String status, OffsetDateTime createdAt) {
        String suffix = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO orders (order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
                                    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
                                    total_amount, recipient_name, recipient_phone, postal_code, address_line1,
                                    payment_expires_at, paid_at, canceled_at, cancel_reason, created_at)
                VALUES (?, ?, ?, ?, 'hash', ?, '상품', '브랜드', 1000, 0, 1000,
                        '수령인', '01000000000', '00000', '주소', ?,
                        CASE WHEN ? IN ('PAID', 'PREPARING', 'SHIPPED', 'DELIVERED') THEN ? ELSE NULL END,
                        CASE WHEN ? = 'CANCELED' THEN ? ELSE NULL END,
                        CASE WHEN ? = 'CANCELED' THEN '테스트 취소' ELSE NULL END,
                        ?)
                RETURNING id
                """,
                Long.class,
                "ORDER-" + suffix, buyerId, dropId, suffix, status,
                createdAt.plusHours(1), status, createdAt, status, createdAt, status, createdAt);
    }

    private void insertPayment(Long orderId, String status, String reconciliationStatus, OffsetDateTime createdAt) {
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO payments (order_id, provider, idempotency_key, client_idempotency_key, request_hash,
                                      amount, status, reconciliation_status, reconciliation_reason,
                                      approved_at, created_at)
                VALUES (?, 'TOSS', ?, ?, 'hash', 1000, ?, ?,
                        CASE WHEN ? = 'NONE' THEN NULL ELSE '테스트' END,
                        CASE WHEN ? IN ('SUCCEEDED', 'CANCELED') THEN ? ELSE NULL END,
                        ?)
                """,
                orderId, suffix, suffix, status, reconciliationStatus,
                reconciliationStatus, status, createdAt, createdAt);
    }
}
