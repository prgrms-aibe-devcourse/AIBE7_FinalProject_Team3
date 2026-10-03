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
import org.example.grab.domain.drop.dto.response.PublicDropStockResponse;
import org.example.grab.domain.drop.dto.response.SellerDropStockResponse;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.wish.service.WishQueryService;
import org.example.grab.global.config.JpaConfig;
import org.example.grab.global.error.BusinessException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GR-52 재고 조회의 PostgreSQL 최신성 검증. 조회 트랜잭션을 분리하고 영속성 컨텍스트를 비워
 * 별도 커밋(수량 변경)이 다음 조회에 반영되는지 확인한다. 실제 주문 연동이 아니라 SQL 수량 변경 검증이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({JpaConfig.class, DropService.class, CategoryService.class, WishQueryService.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
class DropStockQueryIntegrationTest {

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

    @BeforeEach
    void setUp() {
        sellerId = insertSeller();
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '패션') RETURNING id",
                Long.class,
                "TEST-" + UUID.randomUUID());
    }

    @Test
    @DisplayName("판매자 재고 현황은 total·available·reserved·sold를 독립적으로 반환한다")
    void findSellerStocks_returnsQuantities() {
        // given
        Long dropId = publishDrop();

        // when
        SellerDropStockResponse response = dropService.findSellerStocks(sellerId, dropId);

        // then
        assertThat(response.dropId()).isEqualTo(dropId);
        assertThat(response.options()).hasSize(3);
        Map<Long, SellerDropStockResponse.Option> byOptionId = response.options().stream()
                .collect(Collectors.toMap(SellerDropStockResponse.Option::optionId, Function.identity()));
        SellerDropStockResponse.Option cheap = byOptionId.get(optionId(dropId, 0));
        assertThat(cheap.optionName()).isEqualTo("코튼 / 롱");
        assertThat(cheap.totalStock()).isEqualTo(3);
        assertThat(cheap.availableStock()).isEqualTo(3);
        assertThat(cheap.reservedStock()).isZero();
        assertThat(cheap.soldStock()).isZero();
    }

    @Test
    @DisplayName("수량 변경을 별도 트랜잭션으로 커밋하면 다음 조회에서 가용 재고가 줄어든다")
    void reflectsCommittedQuantityChange() {
        // given
        Long dropId = publishDrop();
        Long optionId = optionId(dropId, 0);
        entityManager.clear();

        int before = availableStockOf(dropService.findSellerStocks(sellerId, dropId), optionId);
        assertThat(before).isEqualTo(3);

        // when: 조회와 무관한 별도 커밋으로 확보·판매·보류 수량을 바꾼다
        jdbcTemplate.update(
                """
                UPDATE drop_options
                SET reserved_quantity = 1, sold_quantity = 1, withheld_quantity = 1
                WHERE id = ?
                """, optionId);
        entityManager.clear();

        // then: 전체 3 - 확보 1 - 판매 1 - 보류 1 = 0. 내부 수량은 별도 필드로 유지된다.
        SellerDropStockResponse after = dropService.findSellerStocks(sellerId, dropId);
        SellerDropStockResponse.Option option = findByOptionId(after, optionId);
        assertThat(option.totalStock()).isEqualTo(3);
        assertThat(option.reservedStock()).isEqualTo(1);
        assertThat(option.soldStock()).isEqualTo(1);
        assertThat(option.availableStock()).isZero();
    }

    @Test
    @DisplayName("공개 재고 재조회도 커밋된 수량 변경을 반영하고 가용 0이면 soldOut true다")
    void findPublicStocks_reflectsCommittedChange() {
        // given
        Long dropId = publishDrop();
        Long optionId = optionId(dropId, 0);
        entityManager.clear();

        assertThat(findByOptionId2(dropService.findPublicStocks(dropId), optionId).availableStock()).isEqualTo(3);

        // when
        jdbcTemplate.update(
                "UPDATE drop_options SET sold_quantity = total_quantity WHERE id = ?", optionId);
        entityManager.clear();

        // then
        PublicDropStockResponse.Option option = findByOptionId2(dropService.findPublicStocks(dropId), optionId);
        assertThat(option.availableStock()).isZero();
        assertThat(option.soldOut()).isTrue();
    }

    @Test
    @DisplayName("판매자·공개 API가 같은 커밋 값을 일관되게 반환한다")
    void sellerAndPublicAgree() {
        // given
        Long dropId = publishDrop();
        Long optionId = optionId(dropId, 0);
        jdbcTemplate.update(
                "UPDATE drop_options SET reserved_quantity = 1, sold_quantity = 2 WHERE id = ?", optionId);
        entityManager.clear();

        // when
        SellerDropStockResponse seller = dropService.findSellerStocks(sellerId, dropId);
        PublicDropStockResponse publicResponse = dropService.findPublicStocks(dropId);

        // then
        assertThat(findByOptionId(seller, optionId).availableStock())
                .isEqualTo(findByOptionId2(publicResponse, optionId).availableStock())
                .isZero();
    }

    @Test
    @DisplayName("판매자는 DRAFT·비활성 SKU를 포함하고 공개는 활성 SKU만 반환한다")
    void sellerIncludesDraftAndInactive() {
        // given: 공개되지 않은 DRAFT
        Long draftId = dropService.createDraft(sellerId, requestWithOptions()).getId();
        entityManager.flush();
        entityManager.clear();

        // when & then: 소유자는 DRAFT도 조회한다
        SellerDropStockResponse seller = dropService.findSellerStocks(sellerId, draftId);
        assertThat(seller.options()).hasSize(3);

        // 공개는 DRAFT를 숨긴다
        assertThatThrownBy(() -> dropService.findPublicStocks(draftId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.DROP_NOT_FOUND);
    }

    @Test
    @DisplayName("공개 재고 조회는 활성 SKU만 sortOrder 순으로 반환한다")
    void findPublicStocks_onlyActiveOrdered() {
        // given
        Long dropId = publishDrop();
        entityManager.clear();

        // when
        PublicDropStockResponse response = dropService.findPublicStocks(dropId);

        // then: sortOrder 0, 1(활성) 순으로 오고, sortOrder 2(린넨/롱, 비활성)는 제외된다
        assertThat(response.options()).extracting(PublicDropStockResponse.Option::optionId)
                .containsExactly(optionId(dropId, 0), optionId(dropId, 1));
        assertThat(response.serverTime()).isNotNull();
    }

    @Test
    @DisplayName("CANCELED여도 공개 재고는 200 상당 응답을 만들고 가용을 0으로 바꾸지 않는다")
    void findPublicStocks_allowsCanceled() {
        // given
        Long dropId = publishDrop();
        jdbcTemplate.update(
                """
                UPDATE drops
                SET status = 'CANCELED', closed_at = CURRENT_TIMESTAMP, close_reason = 'SELLER_CANCELED'
                WHERE id = ?
                """, dropId);
        entityManager.clear();

        // when
        PublicDropStockResponse response = dropService.findPublicStocks(dropId);
        SellerDropStockResponse seller = dropService.findSellerStocks(sellerId, dropId);

        // then
        assertThat(response.options()).isNotEmpty();
        assertThat(response.options()).extracting(PublicDropStockResponse.Option::availableStock)
                .containsOnly(3, 5);
        assertThat(seller.options()).isNotEmpty();
    }

    @Test
    @DisplayName("판매자 재고 조회는 자식 컬렉션을 배치로 묶어 쿼리 수가 옵션 수에 비례하지 않는다")
    void findSellerStocks_avoidsNPlusOne() {
        // given
        Long dropId = publishDrop();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        // when
        dropService.findSellerStocks(sellerId, dropId);

        // then: 배치가 풀리면 옵션·valueMap 수에 비례해 늘어난다. 10으로 상한을 둔다.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(10);
    }

    // 그룹·값·SKU의 sort_order를 뒤섞어 저장한다. 공개 상세 통합 테스트와 같은 구성.
    // sortOrder 0: 코튼/롱(활성), 1: 코튼/숏(활성), 2: 린넨/롱(비활성)
    private Long publishDrop() {
        Long dropId = dropService.createDraft(sellerId, requestWithOptions()).getId();
        dropService.publish(sellerId, dropId);
        entityManager.flush();
        entityManager.clear();
        return dropId;
    }

    private DropDraftRequest requestWithOptions() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        return new DropDraftRequest(
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
                                new SelectionRequest("length", "long")), 10000L, 3, true, 0),
                        new OptionRequest(List.of(new SelectionRequest("material", "cotton"),
                                new SelectionRequest("length", "short")), 20000L, 5, true, 1),
                        new OptionRequest(List.of(new SelectionRequest("material", "linen"),
                                new SelectionRequest("length", "long")), 30000L, 2, false, 2)));
    }

    private Long optionId(Long dropId, int sortOrder) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM drop_options WHERE drop_id = ? AND sort_order = ?",
                Long.class, dropId, sortOrder);
    }

    private int availableStockOf(SellerDropStockResponse response, Long optionId) {
        return findByOptionId(response, optionId).availableStock();
    }

    private SellerDropStockResponse.Option findByOptionId(SellerDropStockResponse response, Long optionId) {
        return response.options().stream()
                .filter(option -> option.optionId().equals(optionId))
                .findFirst()
                .orElseThrow();
    }

    private PublicDropStockResponse.Option findByOptionId2(PublicDropStockResponse response, Long optionId) {
        return response.options().stream()
                .filter(option -> option.optionId().equals(optionId))
                .findFirst()
                .orElseThrow();
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
}
