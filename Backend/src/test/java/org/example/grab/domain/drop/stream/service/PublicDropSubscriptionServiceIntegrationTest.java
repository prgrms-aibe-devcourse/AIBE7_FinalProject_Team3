package org.example.grab.domain.drop.stream.service;

import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.config.JpaConfig;
import org.example.grab.global.error.BusinessException;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({JpaConfig.class, PublicDropSubscriptionService.class})
@Testcontainers
class PublicDropSubscriptionServiceIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PublicDropSubscriptionService service;

    @Autowired
    private JdbcTemplate jdbc;

    private Long sellerId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, nickname)
                VALUES (?, 'encoded-password', '구독검증') RETURNING id
                """, Long.class, unique + "@example.com");
        sellerId = jdbc.queryForObject("""
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, '테스트 판매자', ?) RETURNING id
                """, Long.class, userId, unique + "@example.com");
        categoryId = jdbc.queryForObject("""
                INSERT INTO categories (code, name) VALUES (?, '테스트') RETURNING id
                """, Long.class, "STREAM-" + unique);
    }

    @ParameterizedTest
    @EnumSource(value = DropStatus.class, names = {"WISH", "GRAB", "ENDED", "CANCELED"})
    @DisplayName("SKU가 없어도 WISH·GRAB·ENDED·CANCELED는 기존 공개 기준대로 구독 가능하다")
    void acceptsPublicStates(DropStatus status) {
        // given
        Long id = insertDrop(status);

        // when & then
        assertThatCode(() -> service.validatePublicDrops(List.of(id))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("공개 상품 여러 개는 함께 검증하며 관계없는 DRAFT는 영향을 주지 않는다")
    void acceptsMultiplePublicDrops() {
        // given
        Long first = insertDrop(DropStatus.WISH);
        Long second = insertDrop(DropStatus.CANCELED);
        insertDrop(DropStatus.DRAFT);

        // when & then
        assertThatCode(() -> service.validatePublicDrops(List.of(first, second))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DRAFT·없는 ID와 공개 상품의 혼합 요청은 같은 404이며 부분 결과를 노출하지 않는다")
    void hidesDraftAndMissingIdsInMixedRequest() {
        // given
        Long visible = insertDrop(DropStatus.GRAB);
        Long draft = insertDrop(DropStatus.DRAFT);

        // when & then
        for (List<Long> ids : List.of(List.of(draft), List.of(Long.MAX_VALUE),
                List.of(visible, draft), List.of(visible, Long.MAX_VALUE))) {
            assertThatThrownBy(() -> service.validatePublicDrops(ids))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.getErrorCode()).isEqualTo(DropErrorCode.DROP_NOT_FOUND);
                        assertThat(error.getFieldErrors()).isEmpty();
                        assertThat(error.getMessage()).isEqualTo(DropErrorCode.DROP_NOT_FOUND.getMessage());
                    });
        }
    }

    private Long insertDrop(DropStatus status) {
        boolean started = status == DropStatus.GRAB || status == DropStatus.ENDED;
        boolean closed = status == DropStatus.ENDED || status == DropStatus.CANCELED;
        return jdbc.queryForObject("""
                INSERT INTO drops (seller_id, category_id, status, name, description,
                    shipping_fee, shipping_notice, sale_starts_at, sale_ends_at, published_at,
                    grab_started_at, closed_at, close_reason)
                VALUES (?, ?, ?, '구독 테스트', '설명', 0, '배송', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP,
                    CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                    CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                    CASE WHEN ? THEN 'TIME_EXPIRED' ELSE NULL END)
                RETURNING id
                """, Long.class, sellerId, categoryId, status.name(), started, closed, closed);
    }
}
