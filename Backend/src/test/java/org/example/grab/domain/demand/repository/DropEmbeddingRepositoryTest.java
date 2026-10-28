package org.example.grab.domain.demand.repository;

import org.example.grab.domain.demand.embedding.DropEmbeddingText;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.OptionGroup;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.Source;
import org.example.grab.support.TestcontainersConfiguration;
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
@Import(DropEmbeddingRepository.class)
@Testcontainers
class DropEmbeddingRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final String MODEL = "gemini-embedding-001";
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-27T05:00:00Z");

    @Autowired
    private DropEmbeddingRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("공개된 DROP의 카테고리·상품명·설명과 옵션을 그룹·값 표시 순서대로 읽고, DRAFT는 읽지 않는다")
    void findSource_readsPublishedCatalogInOrder() {
        // given: 사이즈 그룹이 색상 그룹보다 앞이고, 값도 sort_order 역순으로 넣는다
        long categoryId = insertCategory("패션");
        long dropId = insertDrop(categoryId, "WISH");
        long size = insertGroup(dropId, "사이즈", 0);
        long color = insertGroup(dropId, "색상", 1);
        insertValue(size, "L", 1);
        insertValue(size, "M", 0);
        insertValue(color, "블랙", 0);
        long draftId = insertDrop(categoryId, "DRAFT");

        // when
        Source source = repository.findSource(dropId).orElseThrow();

        // then
        assertThat(source.categoryName()).isEqualTo("패션");
        assertThat(source.name()).isEqualTo("오버핏 티셔츠");
        assertThat(source.description()).isEqualTo("부드러운 코튼 티셔츠");
        assertThat(source.optionGroups()).containsExactly(
                new OptionGroup("사이즈", List.of("M", "L")),
                new OptionGroup("색상", List.of("블랙")));
        assertThat(repository.findSource(draftId)).isEmpty();
    }

    @Test
    @DisplayName("임베딩을 저장·덮어쓰고, 대상은 임베딩이 없거나 다른 모델로 만든 공개 DROP을 id 순으로 고른다")
    void saveAndFindTargets() {
        // given
        long categoryId = insertCategory("패션");
        long missing = insertDrop(categoryId, "ENDED");
        long current = insertDrop(categoryId, "WISH");
        long oldModel = insertDrop(categoryId, "GRAB");
        long draft = insertDrop(categoryId, "DRAFT");
        repository.save(current, vector(0.1f), MODEL, "a".repeat(64), now);
        repository.save(oldModel, vector(0.1f), "old-model", "b".repeat(64), now);

        // when
        List<Long> targets = repository.findTargets(MODEL, 100);

        // then: 다른 테스트의 DROP이 섞일 수 있어 이 테스트의 DROP만 본다
        assertThat(targets).contains(missing, oldModel).doesNotContain(current, draft);
        assertThat(targets.indexOf(missing)).isLessThan(targets.indexOf(oldModel));
        assertThat(repository.findSourceHash(current)).contains("a".repeat(64));

        // when: 같은 DROP을 다시 저장하면 덮어쓴다
        repository.save(oldModel, vector(0.2f), MODEL, "c".repeat(64), now.plusMinutes(1));

        // then
        assertThat(repository.findSourceHash(oldModel)).contains("c".repeat(64));
        assertThat(repository.findTargets(MODEL, 100)).doesNotContain(oldModel);
        Integer dimensions = jdbcTemplate.queryForObject(
                "SELECT vector_dims(embedding) FROM drop_embeddings WHERE drop_id = ?", Integer.class, oldModel);
        assertThat(dimensions).isEqualTo(DropEmbeddingText.DIMENSIONS);
    }

    @Test
    @DisplayName("저장한 임베딩은 코사인 거리로 가까운 순서를 찾을 수 있다")
    void savedEmbeddings_supportCosineDistance() {
        long categoryId = insertCategory("패션");
        long target = insertDrop(categoryId, "WISH");
        long near = insertDrop(categoryId, "ENDED");
        long far = insertDrop(categoryId, "ENDED");
        repository.save(target, unit(0), MODEL, "a".repeat(64), now);
        repository.save(near, unit(0), MODEL, "b".repeat(64), now);
        repository.save(far, unit(1), MODEL, "c".repeat(64), now);

        List<Long> ordered = jdbcTemplate.queryForList("""
                SELECT e.drop_id
                FROM drop_embeddings e, drop_embeddings t
                WHERE t.drop_id = ? AND e.drop_id IN (?, ?)
                ORDER BY e.embedding <=> t.embedding
                """, Long.class, target, near, far);

        assertThat(ordered).containsExactly(near, far);
    }

    private static float[] vector(float value) {
        float[] vector = new float[DropEmbeddingText.DIMENSIONS];
        java.util.Arrays.fill(vector, value);
        return vector;
    }

    private static float[] unit(int index) {
        float[] vector = new float[DropEmbeddingText.DIMENSIONS];
        vector[index] = 1f;
        return vector;
    }

    private long insertCategory(String name) {
        return jdbcTemplate.queryForObject("INSERT INTO categories (code, name) VALUES (?, ?) RETURNING id",
                Long.class, "TEST-" + UUID.randomUUID(), name);
    }

    private long insertDrop(long categoryId, String status) {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id",
                Long.class, "user-" + suffix + "@example.com", "회원-" + suffix);
        Long sellerId = jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?) RETURNING id",
                Long.class, userId, "contact-" + suffix + "@example.com");
        if (status.equals("DRAFT")) {
            return jdbcTemplate.queryForObject(
                    "INSERT INTO drops (seller_id, category_id, status) VALUES (?, ?, 'DRAFT') RETURNING id",
                    Long.class, sellerId, categoryId);
        }
        OffsetDateTime startsAt = now.minusDays(2);
        boolean ended = status.equals("ENDED");
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee,
                                   shipping_notice, sale_starts_at, sale_ends_at, published_at, grab_started_at,
                                   closed_at, close_reason)
                VALUES (?, ?, ?, '오버핏 티셔츠', '부드러운 코튼 티셔츠', 0, '안내', ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class, sellerId, categoryId, status, startsAt, startsAt.plusDays(1), startsAt.minusDays(5),
                status.equals("WISH") ? null : startsAt, ended ? startsAt.plusDays(1) : null,
                ended ? "TIME_EXPIRED" : null);
    }

    private long insertGroup(long dropId, String name, int sortOrder) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO drop_option_groups (drop_id, name, sort_order) VALUES (?, ?, ?) RETURNING id",
                Long.class, dropId, name, sortOrder);
    }

    private void insertValue(long groupId, String value, int sortOrder) {
        jdbcTemplate.update("INSERT INTO drop_option_values (group_id, value, sort_order) VALUES (?, ?, ?)",
                groupId, value, sortOrder);
    }
}
