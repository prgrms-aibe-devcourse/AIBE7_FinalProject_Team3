package org.example.grab.domain.demand.service;

import org.example.grab.domain.drop.event.DropGrabStartedEvent;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 판매 시작 시점 기록이 실제 DB 데이터로 기본 가정의 분석을 다시 계산해 남기는지 확인한다(DEMAND.md 2.3)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DemandAnalysisSnapshotIntegrationTest {

    @Autowired
    private DemandAnalysisSnapshotRecorder recorder;

    @Autowired
    private DemandAnalysisSnapshotListener listener;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("판매를 시작한 DROP의 WISH 수와 내 실적 전환율로 예상·추천 수량을 계산해 기록한다")
    void record_savesAnalysisAtSaleStart() {
        // given: 본인 종료 DROP 3개, 각각 WISH 10명 중 4명 구매(1개씩) → 전환율 0.4, 구매자당 1.0 → 신뢰도 LOW
        Long sellerId = insertSeller();
        Long categoryId = insertCategory();
        for (int i = 0; i < 3; i++) {
            Long ended = insertDrop(sellerId, categoryId, "ENDED", OffsetDateTime.now().minusDays(2 + i));
            for (int wisher = 0; wisher < 10; wisher++) {
                Long userId = insertWish(ended);
                if (wisher < 4) {
                    insertPaidOrder(ended, userId);
                }
            }
        }
        // 판매를 시작한 DROP: WISH 50명, 옵션 준비 수량 30·30
        Long started = insertDrop(sellerId, categoryId, "GRAB", null);
        insertOption(started, 30, 0);
        insertOption(started, 30, 1);
        for (int wisher = 0; wisher < 50; wisher++) {
            insertWish(started);
        }

        // when
        recorder.record(started);

        // then: expected = round(50 × 0.4 × 1.0) = 20, recommended = ceil(20 × 1.1) = 22
        Map<String, Object> snapshot = snapshot(started);
        assertThat(snapshot.get("conversion_basis")).isEqualTo("OWN");
        assertThat(snapshot.get("active_wish_count")).isEqualTo(50);
        assertThat(snapshot.get("expected_total")).isEqualTo(20);
        assertThat(snapshot.get("recommended_total")).isEqualTo(22);
        assertThat(snapshot.get("prepared_total")).isEqualTo(60);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT result -> 'demand' ->> 'hoursUntilSaleStart' FROM demand_analysis_snapshots WHERE drop_id = ?",
                String.class, started)).isEqualTo("0");
    }

    @Test
    @DisplayName("판매 시작 전환이 커밋되면 이벤트의 DROP마다 기록하고, 롤백되면 기록하지 않는다")
    void grabStartedEvent_recordsOnlyAfterCommit() {
        // given
        Long sellerId = insertSeller();
        Long categoryId = insertCategory();
        Long first = insertDrop(sellerId, categoryId, "GRAB", null);
        insertOption(first, 10, 0);
        Long second = insertDrop(sellerId, categoryId, "GRAB", null);
        insertOption(second, 20, 0);
        Long rolledBack = insertDrop(sellerId, categoryId, "GRAB", null);
        insertOption(rolledBack, 10, 0);

        // when
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new DropGrabStartedEvent(List.of(first, second))));
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new DropGrabStartedEvent(List.of(rolledBack)));
            status.setRollbackOnly();
        });

        // then: 표본이 없어 기준·수량은 없지만 판매 시작 시점 WISH 수와 준비 수량은 남는다
        Map<String, Object> recorded = snapshot(first);
        assertThat(recorded.get("conversion_basis")).isNull();
        assertThat(recorded.get("expected_total")).isNull();
        assertThat(recorded.get("recommended_total")).isNull();
        assertThat(recorded.get("active_wish_count")).isEqualTo(0);
        assertThat(recorded.get("prepared_total")).isEqualTo(10);
        assertThat(snapshot(second).get("prepared_total")).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM demand_analysis_snapshots WHERE drop_id = ?", Integer.class, rolledBack))
                .isZero();
    }

    @Test
    @DisplayName("없는 DROP이 섞여 있어도 예외를 던지지 않고 나머지 DROP은 기록한다")
    void listener_continuesAfterMissingDrop() {
        Long dropId = insertDrop(insertSeller(), insertCategory(), "GRAB", null);
        insertOption(dropId, 5, 0);

        assertThatCode(() -> listener.onDropGrabStarted(new DropGrabStartedEvent(List.of(-1L, dropId))))
                .doesNotThrowAnyException();
        assertThat(snapshot(dropId).get("prepared_total")).isEqualTo(5);
    }

    private Map<String, Object> snapshot(Long dropId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT conversion_basis, active_wish_count, expected_total, recommended_total, prepared_total
                FROM demand_analysis_snapshots WHERE drop_id = ?
                """,
                dropId);
    }

    private Long insertUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id",
                Long.class, "user-" + UUID.randomUUID() + "@example.com", "회원-" + UUID.randomUUID());
    }

    private Long insertSeller() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?) RETURNING id",
                Long.class, insertUser(), "contact-" + UUID.randomUUID() + "@example.com");
    }

    private Long insertCategory() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '테스트') RETURNING id",
                Long.class, "TEST-" + UUID.randomUUID());
    }

    // closedAt이 있으면 판매를 마친 DROP, 없으면 판매를 막 시작한 DROP이다
    private Long insertDrop(Long sellerId, Long categoryId, String status, OffsetDateTime closedAt) {
        OffsetDateTime startsAt = closedAt == null ? OffsetDateTime.now().minusMinutes(1) : closedAt.minusDays(2);
        boolean ended = "ENDED".equals(status);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                                   sale_starts_at, sale_ends_at, published_at, grab_started_at, closed_at, close_reason)
                VALUES (?, ?, ?, '상품', '설명', 0, '안내', ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class, sellerId, categoryId, status, startsAt, startsAt.plusDays(2), startsAt.minusDays(5),
                startsAt, closedAt, ended ? "TIME_EXPIRED" : null);
    }

    private Long insertOption(Long dropId, int total, int sortOrder) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity, sold_quantity,
                                          withheld_quantity, is_active, sort_order)
                VALUES (?, 1000, ?, 0, 0, 0, TRUE, ?)
                RETURNING id
                """,
                Long.class, dropId, total, sortOrder);
    }

    // wishes는 UQ(user_id, drop_id)라 WISH 한 건마다 회원을 새로 만든다
    private Long insertWish(Long dropId) {
        Long userId = insertUser();
        jdbcTemplate.update(
                "INSERT INTO wishes (user_id, drop_id, activated_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                userId, dropId);
        return userId;
    }

    private void insertPaidOrder(Long dropId, Long buyerId) {
        String suffix = UUID.randomUUID().toString();
        OffsetDateTime createdAt = OffsetDateTime.now().minusDays(3);
        Long orderId = jdbcTemplate.queryForObject(
                """
                INSERT INTO orders (order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
                                    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
                                    total_amount, recipient_name, recipient_phone, postal_code, address_line1,
                                    payment_expires_at, paid_at, created_at)
                VALUES (?, ?, ?, ?, 'hash', 'PAID', '상품', '브랜드', 1000, 0, 1000,
                        '수령인', '01000000000', '00000', '주소', ?, ?, ?)
                RETURNING id
                """,
                Long.class, "ORDER-" + suffix, buyerId, dropId, suffix,
                createdAt.plusHours(1), createdAt.plusMinutes(5), createdAt);
        jdbcTemplate.update(
                """
                INSERT INTO order_items (order_id, drop_id, option_id, option_name_snapshot, unit_price, quantity)
                VALUES (?, ?, ?, '옵션', 1000, 1)
                """,
                orderId, dropId, insertOption(dropId, 100, 0));
    }
}
