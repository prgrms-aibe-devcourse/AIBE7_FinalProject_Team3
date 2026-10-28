package org.example.grab.domain.drop.repository;

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

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(RelatedDropRepository.class)
@Testcontainers
class RelatedDropRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-27T05:00:00Z");

    @Autowired
    private RelatedDropRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long sellerId;
    private long categoryId;

    @BeforeEach
    void setUp() {
        sellerId = insertSeller();
        categoryId = jdbcTemplate.queryForObject("INSERT INTO categories (code, name) VALUES (?, '굿즈') RETURNING id",
                Long.class, "TEST-" + UUID.randomUUID());
    }

    @Test
    @DisplayName("함께 WISH는 겹친 활성 WISH 사용자가 기준 이상인 판매 전 WISH DROP만 겹친 수가 많은 순으로 고른다")
    void findCoWishedDropIds_ordersByOverlap() {
        // given: 대상 DROP을 사용자 4명이 WISH
        long target = insertDrop("WISH", NOW.plusDays(1));
        long many = insertDrop("WISH", NOW.plusDays(3));
        long three = insertDrop("WISH", NOW.plusDays(2));
        long two = insertDrop("WISH", NOW.plusDays(2));
        long started = insertDrop("GRAB", NOW.minusHours(1));
        long[] users = {insertUser(), insertUser(), insertUser(), insertUser()};
        for (long user : users) {
            wish(user, target, false);
            wish(user, many, false);
            wish(user, started, false);
        }
        for (int i = 0; i < 3; i++) {
            wish(users[i], three, false);
        }
        wish(users[0], two, false);
        wish(users[1], two, false);
        // 취소한 WISH는 세지 않는다
        wish(users[2], two, true);

        // when
        var ids = repository.findCoWishedDropIds(target, NOW, 3, 4);

        // then: 4명(many) → 3명(three). 2명(two)·판매 시작(started)·대상 자신은 빠진다
        assertThat(ids).containsExactly(many, three);
        assertThat(repository.findCoWishedDropIds(target, NOW, 3, 1)).containsExactly(many);
    }

    @Test
    @DisplayName("비슷한 DROP은 같은 카테고리의 판매 전 WISH DROP 중 임베딩 거리가 기준 이하인 것을 가까운 순으로 고른다")
    void findSimilarDropIds_ordersByDistance() {
        // given
        long target = insertDrop("WISH", NOW.plusDays(1));
        long same = insertDrop("WISH", NOW.plusDays(2));
        long close = insertDrop("WISH", NOW.plusDays(2));
        long far = insertDrop("WISH", NOW.plusDays(2));
        long noEmbedding = insertDrop("WISH", NOW.plusDays(2));
        long ended = insertDrop("ENDED", NOW.minusDays(3));
        long otherCategory = insertDrop("WISH", NOW.plusDays(2));
        jdbcTemplate.update("UPDATE drops SET category_id = (SELECT id FROM categories WHERE id <> ? LIMIT 1) WHERE id = ?",
                categoryId, otherCategory);
        embed(target, 1f, 0f);
        embed(same, 1f, 0f);
        embed(close, 0.8f, 0.6f);
        embed(far, 0f, 1f);
        embed(ended, 1f, 0f);
        embed(otherCategory, 1f, 0f);

        // when
        var ids = repository.findSimilarDropIds(target, NOW, 0.25, 4);

        // then
        assertThat(ids).containsExactly(same, close).doesNotContain(noEmbedding);
        assertThat(repository.findSimilarDropIds(noEmbedding, NOW, 0.25, 4)).isEmpty();
    }

    private long insertSeller() {
        long userId = insertUser();
        return jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?) RETURNING id",
                Long.class, userId, "contact-" + UUID.randomUUID() + "@example.com");
    }

    private long insertUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id",
                Long.class, "user-" + suffix + "@example.com", "회원-" + suffix);
    }

    private long insertDrop(String status, OffsetDateTime saleStartsAt) {
        boolean ended = status.equals("ENDED");
        return jdbcTemplate.queryForObject("""
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                                   sale_starts_at, sale_ends_at, published_at, grab_started_at, closed_at, close_reason)
                VALUES (?, ?, ?, '굿즈', '설명', 0, '안내', ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, sellerId, categoryId, status, saleStartsAt, saleStartsAt.plusHours(2),
                saleStartsAt.minusDays(5), status.equals("WISH") ? null : saleStartsAt,
                ended ? saleStartsAt.plusHours(2) : null, ended ? "TIME_EXPIRED" : null);
    }

    private void wish(long userId, long dropId, boolean canceled) {
        jdbcTemplate.update("INSERT INTO wishes (user_id, drop_id, activated_at, canceled_at) VALUES (?, ?, ?, ?)",
                userId, dropId, NOW.minusDays(1), canceled ? NOW : null);
    }

    // 두 축만 값이 있는 768차원 벡터
    private void embed(long dropId, float first, float second) {
        StringBuilder vector = new StringBuilder("[").append(first).append(',').append(second);
        vector.append(",0".repeat(766)).append(']');
        jdbcTemplate.update("""
                INSERT INTO drop_embeddings (drop_id, embedding, model, source_hash)
                VALUES (?, CAST(? AS vector), 'gemini-embedding-001', ?)
                """, dropId, vector.toString(), "h".repeat(64));
    }
}
