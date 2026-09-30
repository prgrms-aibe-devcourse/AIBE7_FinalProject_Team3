package org.example.grab.domain.dashboard.repository;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        sellerId = insertSeller();
        otherSellerId = insertSeller();
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

    private Long insertSeller() {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id",
                Long.class,
                "seller-" + suffix + "@example.com",
                "판매자-" + suffix);
        return jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?) RETURNING id",
                Long.class,
                userId,
                "contact-" + suffix + "@example.com");
    }

    private void insertDrop(
            Long dropSellerId, String status, String name, OffsetDateTime saleStartsAt, OffsetDateTime saleEndsAt) {
        jdbcTemplate.update(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee,
                                   shipping_notice, sale_starts_at, sale_ends_at, published_at, grab_started_at)
                VALUES (?, ?, ?, ?, '설명', 0, '안내', ?, ?, CURRENT_TIMESTAMP,
                        CASE WHEN ? = 'GRAB' THEN ? ELSE NULL END)
                """,
                dropSellerId, categoryId, status, name, saleStartsAt, saleEndsAt, status, saleStartsAt);
    }
}
