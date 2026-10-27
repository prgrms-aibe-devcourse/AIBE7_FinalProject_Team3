package org.example.grab.domain.demand.repository;

import org.example.grab.domain.demand.embedding.DropEmbeddingText;
import org.example.grab.domain.demand.repository.SimilarSalesRepository.SampleQuery;
import org.example.grab.domain.demand.repository.SimilarSalesRepository.SellerScope;
import org.example.grab.domain.demand.repository.SimilarSalesRepository.SimilarSample;
import org.example.grab.support.TestcontainersConfiguration;
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
@Import(SimilarSalesRepository.class)
@Testcontainers
class SimilarSalesRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-10-27T00:00:00+09:00");
    private static final long NO_DROP = -1;
    private static final float[] BASE = {1, 0};

    @Autowired
    private SimilarSalesRepository repository;

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
    @DisplayName("요청 임베딩과 코사인 거리가 기준 이하인 같은 카테고리 종료 DROP만 가까운 순으로 고르고 매진 시간·판매 시작 WISH를 함께 읽는다")
    void findSamples_ownSimilar() {
        // given: 같은 방향(거리 0), 조금 다른 방향(약 0.05), 45도(약 0.29 → 기준 밖)
        Long exact = insertEndedDrop(sellerId, categoryId, "TIME_EXPIRED", AS_OF.minusDays(3), 2880);
        insertOption(exact, 20000, 10, 6);
        insertEmbedding(exact, 1, 0);
        Long near = insertEndedDrop(sellerId, categoryId, "SOLD_OUT", AS_OF.minusDays(1), 30);
        insertOption(near, 20000, 10, 10);
        insertOption(near, 30000, 5, 5);
        insertEmbedding(near, 0.9f, 0.3f);
        insertSnapshot(near, 40);
        Long far = insertEndedDrop(sellerId, categoryId, "SOLD_OUT", AS_OF.minusDays(1), 30);
        insertOption(far, 20000, 10, 10);
        insertEmbedding(far, 1, 1);
        // 제외: 임베딩 없음, 다른 카테고리, 다른 판매자, 판매자 취소
        insertOption(insertEndedDrop(sellerId, categoryId, "SOLD_OUT", AS_OF.minusDays(1), 30), 20000, 10, 10);
        Long otherCategory = insertEndedDrop(sellerId, insertCategory(), "SOLD_OUT", AS_OF.minusDays(1), 30);
        insertOption(otherCategory, 20000, 10, 10);
        insertEmbedding(otherCategory, 1, 0);
        Long others = insertEndedDrop(otherSellerId, categoryId, "SOLD_OUT", AS_OF.minusDays(1), 30);
        insertOption(others, 20000, 10, 10);
        insertEmbedding(others, 1, 0);

        // when
        List<SimilarSample> samples = repository.findSamples(query(SellerScope.OWN, NO_DROP, true, null));

        // then
        assertThat(samples).containsExactly(
                new SimilarSample(exact, sellerId, 10, 6, false, null, null),
                new SimilarSample(near, sellerId, 15, 15, true, 30, 40));
        assertThat(repository.findSamples(query(SellerScope.OWN, exact, true, null)))
                .extracting(SimilarSample::dropId).containsExactly(near);
    }

    @Test
    @DisplayName("다른 판매자 표본은 본인 DROP과 기준 시점 이후 종료 DROP을 빼고 대표 가격 범위로 거르며, 임베딩이 없으면 최근 종료순이다")
    void findSamples_othersByCategory() {
        // given
        Long recent = insertEndedDrop(otherSellerId, categoryId, "TIME_EXPIRED", AS_OF.minusHours(1), 60);
        insertOption(recent, 20000, 10, 4);
        Long older = insertEndedDrop(otherSellerId, categoryId, "SOLD_OUT", AS_OF.minusDays(2), 7);
        insertOption(older, 15000, 10, 10);
        // 제외: 기준 시점 이후 종료, 가격 범위 밖, 본인
        insertOption(insertEndedDrop(otherSellerId, categoryId, "TIME_EXPIRED", AS_OF, 60), 20000, 10, 4);
        insertOption(insertEndedDrop(otherSellerId, categoryId, "TIME_EXPIRED", AS_OF.minusDays(1), 60), 50000, 10, 4);
        insertOption(insertEndedDrop(sellerId, categoryId, "TIME_EXPIRED", AS_OF.minusDays(1), 60), 20000, 10, 4);

        // when
        List<SimilarSample> samples = repository.findSamples(query(SellerScope.OTHERS, NO_DROP, false,
                new BigDecimal[]{new BigDecimal("14000"), new BigDecimal("26000")}));

        // then
        assertThat(samples).extracting(SimilarSample::dropId).containsExactly(recent, older);
        assertThat(samples.get(1).sellOutMinutes()).isEqualTo(7);
    }

    @Test
    @DisplayName("비슷한 단계 판매자 표본은 종료 DROP이 기준 수 이하인 다른 판매자의 것만 고른다")
    void findSamples_newSellers() {
        // given: 다른 판매자 하나는 종료 DROP 1개, 하나는 4개
        Long newcomer = insertEndedDrop(otherSellerId, categoryId, "TIME_EXPIRED", AS_OF.minusDays(1), 60);
        insertOption(newcomer, 20000, 10, 4);
        Long veteranId = insertSeller();
        for (int i = 0; i < 4; i++) {
            insertOption(insertEndedDrop(veteranId, categoryId, "TIME_EXPIRED", AS_OF.minusDays(2), 60), 20000, 10, 4);
        }

        // when
        List<SimilarSample> samples = repository.findSamples(query(SellerScope.NEW_SELLERS, NO_DROP, false, null));

        // then
        assertThat(samples).extracting(SimilarSample::dropId).containsExactly(newcomer);
    }

    @Test
    @DisplayName("내 종료 DROP 수는 카테고리와 무관하게 세고, 저장된 임베딩과 활성 옵션의 대표 가격을 읽는다")
    void countEmbeddingAndReferencePrice() {
        // given
        insertEndedDrop(sellerId, categoryId, "TIME_EXPIRED", AS_OF.minusDays(1), 60);
        insertEndedDrop(sellerId, insertCategory(), "SOLD_OUT", AS_OF.minusDays(1), 60);
        Long weighted = insertEndedDrop(otherSellerId, categoryId, "TIME_EXPIRED", AS_OF.minusDays(1), 60);
        insertOption(weighted, 10000, 1, 0, true);
        insertOption(weighted, 30000, 3, 0, true);
        insertOption(weighted, 90000, 10, 0, false);
        insertEmbedding(weighted, 1, 0);
        Long unprepared = insertEndedDrop(otherSellerId, categoryId, "TIME_EXPIRED", AS_OF.minusDays(1), 60);
        insertOption(unprepared, 10000, 0, 0, true);
        insertOption(unprepared, 20000, 0, 0, true);

        // when, then
        assertThat(repository.countOwnEndedDrops(sellerId)).isEqualTo(2);
        assertThat(repository.findReferencePrice(weighted)).isEqualTo(25000);
        assertThat(repository.findReferencePrice(unprepared)).isEqualTo(15000);
        assertThat(repository.findEmbedding(weighted)).hasValueSatisfying(text -> assertThat(text).startsWith("[1,0,"));
        assertThat(repository.findEmbedding(unprepared)).isEmpty();
    }

    private SampleQuery query(SellerScope scope, long excludedDropId, boolean similar, BigDecimal[] priceRange) {
        return new SampleQuery(scope, sellerId, categoryId, excludedDropId, AS_OF,
                priceRange == null ? null : priceRange[0], priceRange == null ? null : priceRange[1],
                similar ? vector(BASE[0], BASE[1]) : null, 0.25, 3, 20);
    }

    private static String vector(float x, float y) {
        float[] values = new float[DropEmbeddingText.DIMENSIONS];
        values[0] = x;
        values[1] = y;
        return DropEmbeddingText.toVectorLiteral(values);
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

    // 판매 시작부터 sellingMinutes분 뒤에 끝난 DROP
    private Long insertEndedDrop(Long dropSellerId, Long dropCategoryId, String closeReason, OffsetDateTime closedAt,
                                 int sellingMinutes) {
        OffsetDateTime grabStartedAt = closedAt.minusMinutes(sellingMinutes);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee,
                                   shipping_notice, sale_starts_at, sale_ends_at, published_at, grab_started_at,
                                   closed_at, close_reason)
                VALUES (?, ?, 'ENDED', '상품', '설명', 0, '안내', ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                dropSellerId, dropCategoryId, grabStartedAt, grabStartedAt.plusDays(2), grabStartedAt.minusDays(5),
                grabStartedAt, closedAt, closeReason);
    }

    private void insertOption(Long dropId, long unitPrice, int total, int sold) {
        insertOption(dropId, unitPrice, total, sold, true);
    }

    private void insertOption(Long dropId, long unitPrice, int total, int sold, boolean active) {
        jdbcTemplate.update(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity,
                                          sold_quantity, withheld_quantity, is_active, sort_order)
                VALUES (?, ?, ?, 0, ?, 0, ?, 0)
                """,
                dropId, unitPrice, total, sold, active);
    }

    private void insertEmbedding(Long dropId, float x, float y) {
        jdbcTemplate.update(
                "INSERT INTO drop_embeddings (drop_id, embedding, model, source_hash) VALUES (?, CAST(? AS vector), 'test', 'hash')",
                dropId, vector(x, y));
    }

    private void insertSnapshot(Long dropId, int activeWishCount) {
        jdbcTemplate.update(
                """
                INSERT INTO demand_analysis_snapshots (drop_id, calculation_version, result, active_wish_count,
                                                       prepared_total)
                VALUES (?, 'v1', '{}'::JSONB, ?, 0)
                """,
                dropId, activeWishCount);
    }
}
