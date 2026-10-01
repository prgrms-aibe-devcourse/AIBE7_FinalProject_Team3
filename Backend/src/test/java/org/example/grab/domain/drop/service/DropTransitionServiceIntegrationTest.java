package org.example.grab.domain.drop.service;

import jakarta.persistence.EntityManager;
import org.example.grab.domain.drop.entity.DropStatus;
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
@Import({JpaConfig.class, DropTransitionService.class, DropTransitionBatchService.class})
@Testcontainers
class DropTransitionServiceIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DropTransitionService dropTransitionService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sellerId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        String uniqueValue = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded-password', '판매자') RETURNING id",
                Long.class, uniqueValue + "@example.com");
        sellerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, 'GRAB 판매자', ?) RETURNING id
                """,
                Long.class, userId, "seller-" + uniqueValue + "@example.com");
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '패션') RETURNING id",
                Long.class, "TEST-" + uniqueValue);
    }

    @Test
    @DisplayName("시작·종료 대상이 각각 최대 폴링 주기 안에 GRAB·ENDED로 전환된다")
    void transitionsDueDrops() {
        // given
        Long toStart = insertDrop(DropStatus.WISH, -1, 1);
        Long toEnd = insertDrop(DropStatus.GRAB, -2, -1);
        Long notStarted = insertDrop(DropStatus.WISH, 1, 2);
        Long stillOnSale = insertDrop(DropStatus.GRAB, -1, 1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        long transitioned = dropTransitionService.transition(now);
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(transitioned).isEqualTo(2);
        assertThat(dropRow(toStart).get("status")).isEqualTo("GRAB");
        assertThat(dropRow(toEnd).get("status")).isEqualTo("ENDED");
        assertThat(dropRow(notStarted).get("status")).isEqualTo("WISH");
        assertThat(dropRow(stillOnSale).get("status")).isEqualTo("GRAB");
    }

    @Test
    @DisplayName("판매 기간 전체를 놓친 WISH는 같은 실행에서 GRAB을 거쳐 ENDED까지 전환된다")
    void missedWholeWindowEndsInSameRun() {
        // given
        Long missed = insertDrop(DropStatus.WISH, -3, -1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        long transitioned = dropTransitionService.transition(now);
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(transitioned).isEqualTo(2);
        Map<String, Object> row = dropRow(missed);
        assertThat(row.get("status")).isEqualTo("ENDED");
        assertThat(row.get("close_reason")).isEqualTo("TIME_EXPIRED");
        assertThat(row.get("grab_started_at")).isNotNull();
        assertThat(row.get("closed_at")).isNotNull();
    }

    @Test
    @DisplayName("DRAFT·CANCELED·ENDED는 전환 대상이 아니다")
    void ignoresNonTransitionStatuses() {
        // given
        insertDrop(DropStatus.DRAFT, -1, 1);
        insertDrop(DropStatus.CANCELED, -2, -1);
        insertDrop(DropStatus.ENDED, -2, -1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        long transitioned = dropTransitionService.transition(now);

        // then
        assertThat(transitioned).isZero();
    }

    @Test
    @DisplayName("500건을 넘는 대상이 여러 배치로 나누어 모두 처리된다")
    void processesMoreThanBatchSize() {
        // given
        int total = 501;
        for (int i = 0; i < total; i++) {
            insertDrop(DropStatus.WISH, -1, 1);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        long transitioned = dropTransitionService.transition(now);

        // then
        assertThat(transitioned).isEqualTo(total);
    }

    @Test
    @DisplayName("같은 now로 두 번 실행하면 두 번째는 전환 건수가 0이다")
    void secondRunIsEmpty() {
        // given
        insertDrop(DropStatus.WISH, -1, 1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        long first = dropTransitionService.transition(now);
        long second = dropTransitionService.transition(now);

        // then
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    @Test
    @DisplayName("now가 시작 시각과 같으면 시작 전환, 종료 시각과 같으면 종료 전환이 일어나고 updated_at이 now로 갱신된다")
    void transitionUsesInclusiveBoundariesAndUpdatesTimestamp() {
        // given: 시작 시각 == now인 WISH, 종료 시각 == now인 GRAB을 정확히 설정한다
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Long startsExactlyNow = insertDropAt(DropStatus.WISH, now, now.plusHours(1));
        Long endsExactlyNow = insertDropAt(DropStatus.GRAB, now.minusHours(1), now);

        // when
        long transitioned = dropTransitionService.transition(now);
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(transitioned).isEqualTo(2);
        Map<String, Object> started = dropRow(startsExactlyNow);
        assertThat(started.get("status")).isEqualTo("GRAB");
        assertThat(started.get("grab_started_at")).isNotNull();
        assertThat(updatedAtEquals(startsExactlyNow, now)).isTrue();

        Map<String, Object> ended = dropRow(endsExactlyNow);
        assertThat(ended.get("status")).isEqualTo("ENDED");
        assertThat(ended.get("close_reason")).isEqualTo("TIME_EXPIRED");
        assertThat(updatedAtEquals(endsExactlyNow, now)).isTrue();
    }

    private boolean updatedAtEquals(Long dropId, OffsetDateTime expected) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at = ? FROM drops WHERE id = ?", Boolean.class,
                expected.atZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime(), dropId);
    }

    private Long insertDropAt(DropStatus status, OffsetDateTime saleStartsAt, OffsetDateTime saleEndsAt) {
        boolean grab = status == DropStatus.GRAB || status == DropStatus.ENDED;
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description,
                                   shipping_fee, shipping_notice, sale_starts_at, sale_ends_at,
                                   published_at, grab_started_at)
                VALUES (?, ?, ?, ?, '설명', 3000, '안내', ?, ?, CURRENT_TIMESTAMP,
                        CASE WHEN ? THEN ? ELSE NULL END)
                RETURNING id
                """,
                Long.class, sellerId, categoryId, status.name(), "상품-" + status, saleStartsAt, saleEndsAt,
                grab, saleStartsAt);
    }

    private Long insertDrop(DropStatus status, long startHoursFromNow, long endHoursFromNow) {
        boolean grab = status == DropStatus.GRAB || status == DropStatus.ENDED;
        boolean closed = status == DropStatus.ENDED || status == DropStatus.CANCELED;
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description,
                                   shipping_fee, shipping_notice, sale_starts_at, sale_ends_at,
                                   published_at, grab_started_at, closed_at, close_reason)
                VALUES (?, ?, ?, ?, '설명', 3000, '안내',
                        CURRENT_TIMESTAMP + (? || ' hours')::interval,
                        CURRENT_TIMESTAMP + (? || ' hours')::interval,
                        CURRENT_TIMESTAMP,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - INTERVAL '1 hour' ELSE NULL END,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CASE WHEN ? THEN 'TIME_EXPIRED' ELSE NULL END)
                RETURNING id
                """,
                Long.class, sellerId, categoryId, status.name(), "상품-" + status, startHoursFromNow,
                endHoursFromNow, grab, closed, closed);
    }

    private Map<String, Object> dropRow(Long dropId) {
        return jdbcTemplate.queryForMap("SELECT * FROM drops WHERE id = ?", dropId);
    }
}
