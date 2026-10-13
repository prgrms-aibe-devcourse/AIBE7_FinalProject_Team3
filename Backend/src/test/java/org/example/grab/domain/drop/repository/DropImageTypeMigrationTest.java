package org.example.grab.domain.drop.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * V18(drop_images.image_type) 이관 검증. 이관 직전 버전까지만 적용한 DB에 기존 형태의 이미지를 넣고
 * 나머지를 적용해 기존 행이 값 그대로 GALLERY가 되는지 확인한다.
 * 이관 직전 버전은 V18보다 낮은 마이그레이션 중 가장 높은 버전으로 계산한다.
 * 버전을 고정하면 다른 작업의 V16·V17이 아직 없거나 새로 들어올 때 대상이 어긋나므로 실제 파일 목록에서 고른다.
 */
@Testcontainers
class DropImageTypeMigrationTest {

    private static final MigrationVersion IMAGE_TYPE_VERSION = MigrationVersion.fromVersion("18");

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Test
    @DisplayName("V18 적용 후 기존 이미지는 URL·public_id·순서·대체 설명을 유지한 채 GALLERY가 된다")
    void migratesExistingImagesToGallery() {
        // given: image_type이 없던 시점의 스키마와 데이터
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        flyway(dataSource, versionBeforeImageType(dataSource)).migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Long dropId = insertDraftDrop(jdbcTemplate);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertLegacyImage(jdbcTemplate, dropId, first, "https://img/first.jpg", 0, "정면");
        insertLegacyImage(jdbcTemplate, dropId, second, "https://img/second.jpg", 1, "");

        // when
        flyway(dataSource, "latest").migrate();

        // then
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT public_id, image_url, image_type, sort_order, alt_text
                FROM drop_images WHERE drop_id = ? ORDER BY sort_order
                """,
                dropId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsAllEntriesOf(Map.of(
                "public_id", first, "image_url", "https://img/first.jpg",
                "image_type", "GALLERY", "sort_order", 0, "alt_text", "정면"));
        assertThat(rows.get(1)).containsAllEntriesOf(Map.of(
                "public_id", second, "image_url", "https://img/second.jpg",
                "image_type", "GALLERY", "sort_order", 1, "alt_text", ""));

        // 순서 유일성은 용도별로 바뀐다: DETAIL은 0부터 새로 매길 수 있고 GALLERY 0은 다시 쓸 수 없다
        assertThatCode(() -> insertImage(jdbcTemplate, dropId, "DETAIL", 0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> insertImage(jdbcTemplate, dropId, "GALLERY", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static String versionBeforeImageType(DriverManagerDataSource dataSource) {
        return Arrays.stream(flyway(dataSource, "latest").info().all())
                .map(MigrationInfo::getVersion)
                .filter(version -> version.compareTo(IMAGE_TYPE_VERSION) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow()
                .getVersion();
    }

    private static Long insertDraftDrop(JdbcTemplate jdbcTemplate) {
        String uniqueValue = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded', ?) RETURNING id",
                Long.class, uniqueValue + "@example.com", "판매자-" + uniqueValue);
        Long sellerId = jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, 'GRAB', ?) RETURNING id",
                Long.class, userId, "seller-" + uniqueValue + "@example.com");
        return jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id, status) VALUES (?, 'DRAFT') RETURNING id", Long.class, sellerId);
    }

    private static void insertLegacyImage(JdbcTemplate jdbcTemplate, Long dropId, UUID publicId, String imageUrl,
                                          int sortOrder, String altText) {
        jdbcTemplate.update(
                "INSERT INTO drop_images (public_id, drop_id, image_url, sort_order, alt_text) VALUES (?, ?, ?, ?, ?)",
                publicId, dropId, imageUrl, sortOrder, altText);
    }

    private static void insertImage(JdbcTemplate jdbcTemplate, Long dropId, String imageType, int sortOrder) {
        jdbcTemplate.update(
                """
                INSERT INTO drop_images (drop_id, image_url, image_type, sort_order, alt_text)
                VALUES (?, ?, ?, ?, '상품')
                """,
                dropId, "https://img/" + UUID.randomUUID() + ".jpg", imageType, sortOrder);
    }
}
