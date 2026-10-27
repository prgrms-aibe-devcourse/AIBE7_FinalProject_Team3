package org.example.grab.domain.demand.service;

import org.example.grab.domain.drop.event.DropPublishedEvent;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 공개 시점 기록이 실제 DB 표본으로 다시 계산해 추천·실제 준비 수량·적용 여부를 남기는지 확인한다(DEMAND.md 5.6)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockRecommendationSnapshotIntegrationTest {

    @Autowired
    private StockRecommendationSnapshotRecorder recorder;

    @Autowired
    private StockRecommendationSnapshotListener listener;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("공개한 DROP의 추천을 다시 계산해 기록하고, 옵션별 준비 수량이 추천 배분과 같으면 적용으로 본다")
    void record_savesSnapshotWithApplied() {
        // given: 본인 종료 DROP 3개(판매량 10·20·30, 2만원) → 중간값 20, 준비 권장 ceil(20 × 1.1) = 22
        Long sellerId = insertSeller();
        Long categoryId = insertCategory();
        for (int sold : new int[]{10, 20, 30}) {
            Long ended = insertDrop(sellerId, categoryId, "ENDED", OffsetDateTime.now().minusDays(2));
            insertOption(ended, 20000, 40, sold, 0);
        }
        // 공개한 DROP: 옵션 2개를 같은 수량(11·11)으로 준비 → 균등 배분 11·11과 같음
        Long applied = insertDrop(sellerId, categoryId, "WISH", null);
        insertOption(applied, 20000, 11, 0, 0);
        insertOption(applied, 20000, 11, 0, 1);
        Long notApplied = insertDrop(sellerId, categoryId, "WISH", null);
        insertOption(notApplied, 20000, 30, 0, 0);

        // when
        recorder.record(applied);
        recorder.record(notApplied);

        // then
        Map<String, Object> first = snapshot(applied);
        assertThat(first.get("basis")).isEqualTo("OWN");
        assertThat(first.get("recommended_total")).isEqualTo(22);
        assertThat(first.get("prepared_total")).isEqualTo(22);
        assertThat(first.get("applied")).isEqualTo(true);
        assertThat(snapshot(notApplied).get("applied")).isEqualTo(false);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT result ->> 'calculationVersion' FROM drop_stock_recommendations WHERE drop_id = ?",
                String.class, applied)).isEqualTo("s1");
    }

    @Test
    @DisplayName("공개 트랜잭션이 커밋되면 이벤트를 받아 기록하고, 롤백되면 기록하지 않는다")
    void publishedEvent_recordsOnlyAfterCommit() {
        // given
        Long sellerId = insertSeller();
        Long categoryId = insertCategory();
        Long committed = insertDrop(sellerId, categoryId, "WISH", null);
        insertOption(committed, 20000, 10, 0, 0);
        Long rolledBack = insertDrop(sellerId, categoryId, "WISH", null);
        insertOption(rolledBack, 20000, 10, 0, 0);

        // when
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new DropPublishedEvent(committed)));
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new DropPublishedEvent(rolledBack));
            status.setRollbackOnly();
        });

        // then: 표본이 없어 추천은 없지만 공개 시점 준비 수량은 남는다
        Map<String, Object> recorded = snapshot(committed);
        assertThat(recorded.get("basis")).isNull();
        assertThat(recorded.get("recommended_total")).isNull();
        assertThat(recorded.get("prepared_total")).isEqualTo(10);
        assertThat(recorded.get("applied")).isEqualTo(false);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM drop_stock_recommendations WHERE drop_id = ?", Integer.class, rolledBack))
                .isZero();
    }

    @Test
    @DisplayName("카테고리가 없는 DROP은 기록하지 않고, 없는 DROP이어도 공개 이후 처리에서 예외를 던지지 않는다")
    void listener_skipsWithoutFailing() {
        // 공개된 DROP은 DB 제약상 카테고리가 있으므로, 카테고리 없는 행은 DRAFT로 만든다
        Long noCategory = jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id, status) VALUES (?, 'DRAFT') RETURNING id", Long.class, insertSeller());
        insertOption(noCategory, 1000, 5, 0, 0);

        assertThatCode(() -> listener.onDropPublished(new DropPublishedEvent(noCategory))).doesNotThrowAnyException();
        assertThatCode(() -> listener.onDropPublished(new DropPublishedEvent(-1L))).doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM drop_stock_recommendations WHERE drop_id = ?", Integer.class, noCategory))
                .isZero();
    }

    private Map<String, Object> snapshot(Long dropId) {
        return jdbcTemplate.queryForMap(
                "SELECT basis, recommended_total, prepared_total, applied FROM drop_stock_recommendations WHERE drop_id = ?",
                dropId);
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

    private Long insertDrop(Long sellerId, Long categoryId, String status, OffsetDateTime closedAt) {
        OffsetDateTime startsAt = closedAt == null ? OffsetDateTime.now().plusDays(3) : closedAt.minusDays(2);
        boolean ended = "ENDED".equals(status);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                                   sale_starts_at, sale_ends_at, published_at, grab_started_at, closed_at, close_reason)
                VALUES (?, ?, ?, '상품', '설명', 0, '안내', ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class, sellerId, categoryId, status, startsAt, startsAt.plusDays(2), startsAt.minusDays(5),
                ended ? startsAt : null, closedAt, ended ? "TIME_EXPIRED" : null);
    }

    private void insertOption(Long dropId, long unitPrice, int total, int sold, int sortOrder) {
        jdbcTemplate.update(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity, sold_quantity,
                                          withheld_quantity, is_active, sort_order)
                VALUES (?, ?, ?, 0, ?, 0, TRUE, ?)
                """,
                dropId, unitPrice, total, sold, sortOrder);
    }
}
