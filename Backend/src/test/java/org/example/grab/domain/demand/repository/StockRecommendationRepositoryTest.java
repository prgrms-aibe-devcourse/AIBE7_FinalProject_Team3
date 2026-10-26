package org.example.grab.domain.demand.repository;

import org.example.grab.domain.demand.dto.SalesSample;
import org.example.grab.domain.demand.repository.StockRecommendationRepository.PriceRange;
import org.example.grab.domain.demand.repository.StockRecommendationRepository.PublishedDrop;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(StockRecommendationRepository.class)
@Testcontainers
class StockRecommendationRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-10-26T00:00:00+09:00");
    private static final PriceRange AROUND_20000 = new PriceRange(new BigDecimal("14000"), new BigDecimal("26000"));

    @Autowired
    private StockRecommendationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sellerId;
    private Long otherSellerId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        sellerId = insertSeller();
        otherSellerId = insertSeller();
        categoryId = insertCategory();
    }

    @Test
    @DisplayName("내 실적 표본은 같은 카테고리의 판매 기간 만료 종료 DROP만 대표 가격 범위로 골라 최근 종료순으로 반환한다")
    void findOwnSales_filtersAndOrders() {
        // given: 대표 가격 = (10000×1 + 30000×3) / 4 = 25000 → 범위 안
        Long weighted = insertDrop(sellerId, categoryId, "ENDED", "TIME_EXPIRED", AS_OF.minusDays(1));
        insertOption(weighted, 10000, 10, 10);
        insertOption(weighted, 30000, 30, 12);
        Long older = insertDrop(sellerId, categoryId, "ENDED", "TIME_EXPIRED", AS_OF.minusDays(5));
        insertOption(older, 20000, 20, 20);
        // 제외: 가격 범위 밖, 판매자 취소, 다른 카테고리, 다른 판매자, 준비 수량 0
        insertOption(insertDrop(sellerId, categoryId, "ENDED", "TIME_EXPIRED", AS_OF.minusDays(2)), 50000, 10, 5);
        insertOption(insertDrop(sellerId, categoryId, "CANCELED", "SELLER_CANCELED", AS_OF.minusDays(2)), 20000, 10, 0);
        insertOption(insertDrop(sellerId, insertCategory(), "ENDED", "TIME_EXPIRED", AS_OF.minusDays(2)), 20000, 10, 5);
        insertOption(insertDrop(otherSellerId, categoryId, "ENDED", "TIME_EXPIRED", AS_OF.minusDays(2)), 20000, 10, 5);
        insertOption(insertDrop(sellerId, categoryId, "ENDED", "TIME_EXPIRED", AS_OF.minusDays(2)), 20000, 0, 0);

        // when
        List<SalesSample> samples = repository.findOwnSales(sellerId, categoryId, AROUND_20000, 20);

        // then: 판매량 합·준비 수량 합·품절(가용 0) 여부를 DROP 단위로 계산한다
        assertThat(samples).containsExactly(
                new SalesSample(sellerId, 25000, 40, 22, false),
                new SalesSample(sellerId, 20000, 20, 20, true));
        assertThat(repository.findOwnSales(sellerId, categoryId, AROUND_20000, 1)).hasSize(1);
        assertThat(repository.findOwnSales(sellerId, categoryId, null, 20)).hasSize(3);
    }

    @Test
    @DisplayName("시장 표본은 본인 DROP과 기준 시점 이후 종료 DROP을 빼고 판매자 ID와 함께 반환한다")
    void findMarketSales_excludesOwnAndRecent() {
        // given
        insertOption(insertDrop(otherSellerId, categoryId, "ENDED", "TIME_EXPIRED", AS_OF.minusHours(1)), 20000, 10, 4);
        insertOption(insertDrop(otherSellerId, categoryId, "ENDED", "TIME_EXPIRED", AS_OF), 20000, 10, 4);
        insertOption(insertDrop(sellerId, categoryId, "ENDED", "TIME_EXPIRED", AS_OF.minusHours(1)), 20000, 10, 4);

        // when
        List<SalesSample> samples = repository.findMarketSales(sellerId, categoryId, AS_OF, AROUND_20000, 20);

        // then
        assertThat(samples).containsExactly(new SalesSample(otherSellerId, 20000, 10, 4, false));
    }

    @Test
    @DisplayName("공개한 DROP의 활성 옵션 가격·수량을 정렬 순서대로 읽고, 기록은 DROP당 한 건만 남긴다")
    void publishedDropAndSnapshot() {
        // given
        Long dropId = insertDrop(sellerId, categoryId, "WISH", null, null);
        Long second = insertOption(dropId, 20000, 15, 0, 1, true);
        Long first = insertOption(dropId, 20000, 30, 0, 0, true);
        insertOption(dropId, 20000, 99, 0, 2, false);

        // when
        PublishedDrop drop = repository.findPublishedDrop(dropId).orElseThrow();
        repository.saveSnapshot(dropId, "s1", "OWN", "{}", 44, 45, "[]", false, AS_OF);
        repository.saveSnapshot(dropId, "s1", null, "{}", null, 45, "[]", false, AS_OF);

        // then
        assertThat(drop.categoryId()).isEqualTo(categoryId);
        assertThat(drop.options()).extracting(StockRecommendationRepository.PublishedOption::optionId)
                .containsExactly(first, second);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) || ':' || MAX(basis) FROM drop_stock_recommendations WHERE drop_id = ?",
                String.class, dropId)).isEqualTo("1:OWN");
    }

    private Long insertSeller() {
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id",
                Long.class, "user-" + UUID.randomUUID() + "@example.com", "회원-" + UUID.randomUUID());
        return jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?) RETURNING id",
                Long.class, userId, "contact-" + UUID.randomUUID() + "@example.com");
    }

    private Long insertCategory() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '테스트') RETURNING id",
                Long.class, "TEST-" + UUID.randomUUID());
    }

    private Long insertDrop(Long dropSellerId, Long dropCategoryId, String status, String closeReason,
                            OffsetDateTime closedAt) {
        OffsetDateTime startsAt = closedAt == null ? AS_OF.plusDays(3) : closedAt.minusDays(2);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee,
                                   shipping_notice, sale_starts_at, sale_ends_at, published_at, grab_started_at,
                                   closed_at, close_reason, cancel_reason)
                VALUES (?, ?, ?, '상품', '설명', 0, '안내', ?, ?, ?,
                        CASE WHEN ? = 'ENDED' THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END,
                        ?, ?, CASE WHEN ? = 'SELLER_CANCELED' THEN '테스트 취소' ELSE NULL END)
                RETURNING id
                """,
                Long.class,
                dropSellerId, dropCategoryId, status, startsAt, startsAt.plusDays(2), startsAt.minusDays(5),
                status, startsAt, closedAt, closeReason, closeReason);
    }

    private Long insertOption(Long dropId, long unitPrice, int total, int sold) {
        return insertOption(dropId, unitPrice, total, sold, 0, true);
    }

    private Long insertOption(Long dropId, long unitPrice, int total, int sold, int sortOrder, boolean active) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity,
                                          sold_quantity, withheld_quantity, is_active, sort_order)
                VALUES (?, ?, ?, 0, ?, 0, ?, ?)
                RETURNING id
                """,
                Long.class, dropId, unitPrice, total, sold, active, sortOrder);
    }
}
