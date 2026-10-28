package org.example.grab.domain.payment.repository;

import org.example.grab.support.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * V21(payment_cancellations.next_retry_at 채우기) 검증. 이관 직전 버전까지만 적용한 DB에 next_retry_at 없이 저장된
 * 취소 기록을 넣고 나머지를 적용해, REQUESTED에만 created_at + 1분이 채워지는지 확인한다.
 * 이관 직전 버전은 V21보다 낮은 마이그레이션 중 가장 높은 버전으로 계산한다(DropImageTypeMigrationTest와 같은 이유).
 */
@Testcontainers
class PaymentCancellationRetryMigrationTest {

    private static final MigrationVersion RETRY_FILL_VERSION = MigrationVersion.fromVersion("21");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.of(2026, 10, 1, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime UNKNOWN_RETRY_AT = OffsetDateTime.of(2026, 10, 1, 9, 5, 0, 0, ZoneOffset.UTC);

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @Test
    @DisplayName("V21 적용 후 next_retry_at이 없던 REQUESTED는 created_at + 1분이 되고, UNKNOWN 값과 끝난 취소는 그대로다")
    void fillsNextRetryAtOfRequested() {
        // given: REQUESTED가 next_retry_at 없이 저장되던 시점의 스키마와 데이터
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        flyway(dataSource, versionBeforeRetryFill(dataSource)).migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Long paymentId = insertPayment(jdbcTemplate);
        Long requested = insertCancellation(jdbcTemplate, paymentId, "REQUESTED", null, null);
        Long unknown = insertCancellation(jdbcTemplate, paymentId, "UNKNOWN", UNKNOWN_RETRY_AT, null);
        Long succeeded = insertCancellation(jdbcTemplate, paymentId, "SUCCEEDED", null, CREATED_AT.plusSeconds(3));

        // when
        flyway(dataSource, "latest").migrate();

        // then
        assertThat(nextRetryAt(jdbcTemplate, requested)).isAtSameInstantAs(CREATED_AT.plusMinutes(1));
        assertThat(nextRetryAt(jdbcTemplate, unknown)).isAtSameInstantAs(UNKNOWN_RETRY_AT);
        assertThat(nextRetryAt(jdbcTemplate, succeeded)).isNull();
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static String versionBeforeRetryFill(DriverManagerDataSource dataSource) {
        return Arrays.stream(flyway(dataSource, "latest").info().all())
                .map(MigrationInfo::getVersion)
                .filter(version -> version.compareTo(RETRY_FILL_VERSION) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow()
                .getVersion();
    }

    private static Long insertPayment(JdbcTemplate jdbcTemplate) {
        String uniqueValue = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded', ?) RETURNING id",
                Long.class, uniqueValue + "@example.com", "b" + uniqueValue.substring(0, 8));
        Long sellerId = jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, 'GRAB', ?) RETURNING id",
                Long.class, userId, "seller-" + uniqueValue + "@example.com");
        Long dropId = jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id) VALUES (?) RETURNING id", Long.class, sellerId);
        Long orderId = jdbcTemplate.queryForObject(
                """
                INSERT INTO orders (order_number, buyer_id, drop_id, idempotency_key, request_hash,
                                    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
                                    total_amount, recipient_name, recipient_phone, postal_code, address_line1,
                                    payment_expires_at)
                VALUES (?, ?, ?, ?, ?, '한정판 후드', 'GRAB', 30000, 3000, 33000, '홍길동', '010-1234-5678',
                        '06236', '서울시 강남구', ?)
                RETURNING id
                """,
                Long.class, "ORD-" + uniqueValue, userId, dropId, "order-" + uniqueValue, "a".repeat(64),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO payments (order_id, provider, idempotency_key, client_idempotency_key, request_hash, amount)
                VALUES (?, 'TOSS', ?, ?, ?, 33000)
                RETURNING id
                """,
                Long.class, orderId, "server-" + uniqueValue, "client-" + uniqueValue, "b".repeat(64));
    }

    private static Long insertCancellation(JdbcTemplate jdbcTemplate, Long paymentId, String status,
                                           OffsetDateTime nextRetryAt, OffsetDateTime completedAt) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO payment_cancellations (payment_id, purpose, idempotency_key, amount, reason, status,
                                                   next_retry_at, completed_at, created_at, updated_at)
                VALUES (?, 'ORDER_CANCEL', ?, 33000, '단순 변심', ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class, paymentId, UUID.randomUUID().toString(), status, nextRetryAt, completedAt,
                CREATED_AT, CREATED_AT);
    }

    private static OffsetDateTime nextRetryAt(JdbcTemplate jdbcTemplate, Long cancellationId) {
        return jdbcTemplate.queryForObject(
                "SELECT next_retry_at FROM payment_cancellations WHERE id = ?", OffsetDateTime.class, cancellationId);
    }
}
