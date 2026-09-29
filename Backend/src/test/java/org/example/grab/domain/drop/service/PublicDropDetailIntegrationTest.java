package org.example.grab.domain.drop.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.dto.request.OptionGroupRequest;
import org.example.grab.domain.drop.dto.request.OptionRequest;
import org.example.grab.domain.drop.dto.request.OptionValueRequest;
import org.example.grab.domain.drop.dto.request.SelectionRequest;
import org.example.grab.domain.drop.dto.request.ShippingRequest;
import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse;
import org.example.grab.domain.drop.dto.response.common.DropOptionGroupResponse;
import org.example.grab.domain.drop.dto.response.common.DropOptionSelectionResponse;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.wish.WishNotice;
import org.example.grab.domain.wish.service.WishQueryService;
import org.example.grab.global.config.JpaConfig;
import org.example.grab.global.error.BusinessException;
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
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({JpaConfig.class, DropService.class, CategoryService.class, WishQueryService.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
class PublicDropDetailIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DropService dropService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Long sellerId;
    private Long categoryId;
    private Long buyerId;

    @BeforeEach
    void setUp() {
        sellerId = insertSeller();
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '패션') RETURNING id",
                Long.class,
                "TEST-" + UUID.randomUUID()
        );
        buyerId = insertBuyer();
    }

    @Test
    @DisplayName("공개 상세는 그룹·값·SKU를 sortOrder 순으로, 활성 SKU만 반환한다")
    void findPublicDrop_ordersGroupsValuesAndActiveSkus() {
        // given
        Long dropId = publishDrop();

        // when
        PublicDropDetailResponse detail = dropService.findPublicDrop(dropId);

        // then
        assertThat(detail.optionGroups()).extracting(DropOptionGroupResponse::name)
                .containsExactly("소재", "길이");
        assertThat(detail.optionGroups().get(0).values()).extracting(DropOptionGroupResponse.Value::value)
                .containsExactly("코튼", "린넨");
        // 비활성 SKU(린넨/롱, 30000)는 제외되고 sortOrder 0, 1 순으로 온다.
        assertThat(detail.options()).extracting(PublicDropDetailResponse.Option::unitPrice)
                .containsExactly(10000L, 20000L);
        assertThat(detail.options().get(0).selections()).extracting(DropOptionSelectionResponse::groupId)
                .containsExactly(detail.optionGroups().get(0).groupId(), detail.optionGroups().get(1).groupId());
        assertThat(detail.minPrice()).isEqualTo(10000L);
        assertThat(detail.wishNotice()).isEqualTo(WishNotice.MESSAGE);
        assertThat(detail.actions()).isEqualTo(new PublicDropDetailResponse.Actions(true, true, false));
        assertThat(detail.serverTime()).isNotNull();
    }

    @Test
    @DisplayName("availableStock은 total - reserved - sold - withheld이고 SKU soldOut은 재고 0일 때 true")
    void findPublicDrop_calculatesAvailableStock() {
        // given
        Long dropId = publishDrop();
        Long cheapOptionId = jdbcTemplate.queryForObject(
                "SELECT id FROM drop_options WHERE drop_id = ? AND sort_order = 0", Long.class, dropId);
        jdbcTemplate.update(
                """
                UPDATE drop_options
                SET reserved_quantity = 1, sold_quantity = 1, withheld_quantity = 1
                WHERE id = ?
                """,
                cheapOptionId);

        // when
        PublicDropDetailResponse detail = dropService.findPublicDrop(dropId);

        // then
        PublicDropDetailResponse.Option cheap = detail.options().stream()
                .filter(option -> option.unitPrice() == 10000L)
                .findFirst()
                .orElseThrow();
        assertThat(cheap.availableStock()).isEqualTo(0);
        assertThat(cheap.soldOut()).isTrue();
        // 다른 활성 SKU에 재고가 남아 있어 DROP 전체는 품절이 아니다.
        assertThat(detail.soldOut()).isFalse();
    }

    @Test
    @DisplayName("wishCount는 취소되지 않은 WISH만 센다")
    void findPublicDrop_countsOnlyActiveWishes() {
        // given
        Long dropId = publishDrop();
        insertWish(buyerId, dropId, false);
        insertWish(insertBuyer(), dropId, false);
        insertWish(insertBuyer(), dropId, true);

        // when
        PublicDropDetailResponse detail = dropService.findPublicDrop(dropId);

        // then
        assertThat(detail.wishCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("상태별 actions가 0절 표와 일치한다")
    void findPublicDrop_actionsByStatus() {
        // given
        Long wishDropId = publishDrop();
        Long grabDropId = publishDrop();
        jdbcTemplate.update(
                """
                UPDATE drops
                SET status = 'GRAB', grab_started_at = CURRENT_TIMESTAMP,
                    sale_starts_at = CURRENT_TIMESTAMP - INTERVAL '1 hour',
                    sale_ends_at = CURRENT_TIMESTAMP + INTERVAL '1 hour'
                WHERE id = ?
                """, grabDropId);
        Long endedDropId = publishDrop();
        jdbcTemplate.update(
                """
                UPDATE drops
                SET status = 'ENDED', closed_at = CURRENT_TIMESTAMP, close_reason = 'TIME_EXPIRED',
                    grab_started_at = CURRENT_TIMESTAMP - INTERVAL '2 hours',
                    sale_starts_at = CURRENT_TIMESTAMP - INTERVAL '2 hours',
                    sale_ends_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                WHERE id = ?
                """, endedDropId);
        Long canceledDropId = publishDrop();
        jdbcTemplate.update(
                """
                UPDATE drops
                SET status = 'CANCELED', closed_at = CURRENT_TIMESTAMP, close_reason = 'SELLER_CANCELED'
                WHERE id = ?
                """, canceledDropId);
        entityManager.clear();

        // when & then
        assertThat(dropService.findPublicDrop(wishDropId).actions())
                .isEqualTo(new PublicDropDetailResponse.Actions(true, true, false));

        PublicDropDetailResponse grab = dropService.findPublicDrop(grabDropId);
        assertThat(grab.status()).isEqualTo(DropStatus.GRAB);
        assertThat(grab.actions()).isEqualTo(new PublicDropDetailResponse.Actions(false, false, true));

        assertThat(dropService.findPublicDrop(endedDropId).actions())
                .isEqualTo(new PublicDropDetailResponse.Actions(false, false, false));

        PublicDropDetailResponse canceled = dropService.findPublicDrop(canceledDropId);
        assertThat(canceled.status()).isEqualTo(DropStatus.CANCELED);
        assertThat(canceled.actions()).isEqualTo(new PublicDropDetailResponse.Actions(false, false, false));
    }

    @Test
    @DisplayName("DRAFT 상세 조회는 DROP_NOT_FOUND로 숨긴다")
    void findPublicDrop_hidesDraft() {
        // given
        Long draftId = dropService.createDraft(sellerId, emptyRequest()).getId();
        entityManager.flush();
        entityManager.clear();

        // when & then
        assertThatThrownBy(() -> dropService.findPublicDrop(draftId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_FOUND);
    }

    @Test
    @DisplayName("상세 조회는 자식 컬렉션을 @BatchSize로 묶어 쿼리 수를 고정한다(N+1 아님)")
    void findPublicDrop_avoidsNPlusOne() {
        // given
        Long dropId = publishDrop();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        // when
        dropService.findPublicDrop(dropId);

        // then: DROP·카테고리·WISH 수 + 자식 컬렉션 배치(이미지·그룹·값·옵션·valueMap)로 8회.
        // 배치가 풀리면 자식 수에 비례해 늘어나므로 10으로 상한을 둔다.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(10);
    }

    // 그룹·값·SKU의 sort_order를 뒤섞어 저장해도 응답은 sortOrder 순이어야 한다.
    private Long publishDrop() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        DropDraftRequest request = new DropDraftRequest(
                "상품", "설명", List.of("https://example.com/a.jpg"), categoryId, start, start.plusHours(2),
                new ShippingRequest(3000L, "출고 안내"),
                List.of(
                        new OptionGroupRequest("material", "소재", 0, List.of(
                                new OptionValueRequest("cotton", "코튼", 0),
                                new OptionValueRequest("linen", "린넨", 1))),
                        new OptionGroupRequest("length", "길이", 1, List.of(
                                new OptionValueRequest("short", "숏", 0),
                                new OptionValueRequest("long", "롱", 1)))),
                List.of(
                        new OptionRequest(List.of(new SelectionRequest("material", "cotton"),
                                new SelectionRequest("length", "short")), 20000L, 5, true, 1),
                        new OptionRequest(List.of(new SelectionRequest("material", "cotton"),
                                new SelectionRequest("length", "long")), 10000L, 3, true, 0),
                        new OptionRequest(List.of(new SelectionRequest("material", "linen"),
                                new SelectionRequest("length", "long")), 30000L, 2, false, 2)));
        Long dropId = dropService.createDraft(sellerId, request).getId();
        dropService.publish(sellerId, dropId);
        entityManager.flush();
        entityManager.clear();
        return dropId;
    }

    private DropDraftRequest emptyRequest() {
        return new DropDraftRequest(null, null, null, null, null, null, null, null, null);
    }

    private Long insertSeller() {
        String uniqueValue = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname)
                VALUES (?, 'encoded-password', ?)
                RETURNING id
                """,
                Long.class,
                uniqueValue + "@example.com",
                "판매자-" + uniqueValue);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, 'GRAB 판매자', ?)
                RETURNING id
                """,
                Long.class,
                userId,
                "seller-" + uniqueValue + "@example.com");
    }

    private Long insertBuyer() {
        String uniqueValue = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded-password', ?) RETURNING id",
                Long.class,
                "buyer-" + uniqueValue + "@example.com",
                "구매자-" + uniqueValue);
    }

    private void insertWish(Long userId, Long dropId, boolean canceled) {
        jdbcTemplate.update(
                """
                INSERT INTO wishes (user_id, drop_id, activated_at, canceled_at)
                VALUES (?, ?, CURRENT_TIMESTAMP - INTERVAL '2 hours',
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - INTERVAL '1 hour' ELSE NULL END)
                """,
                userId, dropId, canceled);
    }
}
