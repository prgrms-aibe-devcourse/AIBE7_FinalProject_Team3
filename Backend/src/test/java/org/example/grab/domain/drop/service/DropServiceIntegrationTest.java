package org.example.grab.domain.drop.service;

import jakarta.persistence.EntityManager;
import org.example.grab.global.config.JpaConfig;
import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.dto.request.OptionGroupRequest;
import org.example.grab.domain.drop.dto.request.OptionRequest;
import org.example.grab.domain.drop.dto.request.OptionValueRequest;
import org.example.grab.domain.drop.dto.request.SelectionRequest;
import org.example.grab.domain.drop.dto.request.ShippingRequest;
import org.example.grab.domain.drop.dto.response.SellerDropDetailResponse;
import org.example.grab.domain.drop.dto.response.SellerDropListResponse;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropImage;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
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
@Import({JpaConfig.class, DropService.class, CategoryService.class})
@Testcontainers
class DropServiceIntegrationTest {

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
    private DropRepository dropRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sellerId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        sellerId = insertSeller();
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '패션') RETURNING id",
                Long.class,
                "TEST-" + UUID.randomUUID()
        );
    }

    @Test
    @DisplayName("필수 항목이 비어 있어도 DRAFT로 저장된다")
    void createDraft_persistsDraft() {
        // given
        DropDraftRequest request = emptyRequest();

        // when
        Drop saved =         dropService.createDraft(sellerId, request);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(DropStatus.DRAFT);

        entityManager.clear();
        Drop found = dropRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(DropStatus.DRAFT);
    }

    @Test
    @DisplayName("옵션 조합과 이미지·옵션 그룹·값 정렬 순서가 요청대로 저장된다")
    void createDraft_persistsOptionsWithSortOrder() {
        // when
        Drop saved =         dropService.createDraft(sellerId, fullRequest());

        // then
        entityManager.clear();
        Drop found = dropRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getImages()).extracting(DropImage::getSortOrder).containsExactly(0, 1);
        assertThat(found.getOptionGroups()).hasSize(2);
        assertThat(found.getOptionGroups().get(0).getValues()).hasSize(2);
        assertThat(found.getOptionGroups().get(0).getValues().get(0).getValue()).isEqualTo("코튼");
        assertThat(found.getOptions()).hasSize(2);
        assertThat(found.getOptions().get(0).getValueMaps()).hasSize(2);
        assertThat(found.getOptions().get(0).getAvailableQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("DRAFT 수정 내용이 저장된다")
    void updateDraft_persistsChanges() {
        // given
        Drop saved =         dropService.createDraft(sellerId, fullRequest());
        entityManager.flush();
        entityManager.clear();

        // when
        dropService.updateDraft(sellerId, saved.getId(),
                new DropDraftRequest("수정된 이름", null, null, null, null, null, null, null, null));
        entityManager.flush();
        entityManager.clear();

        // then
        Drop found = dropRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("수정된 이름");
        assertThat(found.getOptions()).hasSize(2);
    }

    @Test
    @DisplayName("일정 한쪽만 바꿔 시작이 종료 이후가 되면 INVALID_SCHEDULE")
    void updateDraft_rejectsInvalidScheduleAfterMerge() {
        // given
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        OffsetDateTime end = start.plusHours(2);
        Drop saved =         dropService.createDraft(sellerId,
                new DropDraftRequest(null, null, null, null, start, end, null, null, null));
        entityManager.flush();

        // when & then
        assertThatThrownBy(() -> dropService.updateDraft(sellerId, saved.getId(),
                new DropDraftRequest(null, null, null, null, end.plusHours(1), null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DropErrorCode.INVALID_SCHEDULE);
    }

    @Test
    @DisplayName("수정 시 이미지·옵션을 전체 교체해도 제약 위반 없이 저장된다")
    void updateDraft_replacesChildren() {
        // given
        Drop saved =         dropService.createDraft(sellerId, fullRequest());
        entityManager.flush();
        entityManager.clear();

        OptionGroupRequest color = new OptionGroupRequest("color", "색상", 0,
                List.of(new OptionValueRequest("black", "블랙", 0)));
        OptionRequest option = new OptionRequest(
                List.of(new SelectionRequest("color", "black")), 5000L, 3, true, 0);
        DropDraftRequest update = new DropDraftRequest(null, null,
                List.of("https://example.com/c.jpg", "https://example.com/d.jpg"),
                null, null, null, null, List.of(color), List.of(option));

        // when
        dropService.updateDraft(sellerId, saved.getId(), update);
        entityManager.flush();
        entityManager.clear();

        // then
        Drop found = dropRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getImages()).extracting(DropImage::getImageUrl)
                .containsExactly("https://example.com/c.jpg", "https://example.com/d.jpg");
        assertThat(found.getOptionGroups()).singleElement()
                .extracting("name").isEqualTo("색상");
        assertThat(found.getOptions()).hasSize(1);
    }

    @Test
    @DisplayName("DB에서 다시 읽은 DRAFT를 공개하면 WISH와 publishedAt이 저장된다")
    void publish_persistsWish() {
        // given: 영속성 컨텍스트를 비워 LAZY 로딩된 옵션 구조로 검증이 동작하는지 확인한다.
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        DropDraftRequest full = fullRequest();
        DropDraftRequest request = new DropDraftRequest(full.name(), full.description(), full.imageUrls(),
                full.categoryId(), start, start.plusDays(1), new ShippingRequest(3000L, "안내"),
                full.optionGroups(), full.options());
        Long dropId =         dropService.createDraft(sellerId, request).getId();
        entityManager.flush();
        entityManager.clear();

        // when
        dropService.publish(sellerId, dropId);
        entityManager.flush();
        entityManager.clear();

        // then
        Drop found = dropRepository.findById(dropId).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(DropStatus.WISH);
        assertThat(found.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 categoryId로 저장하면 500이 아니라 VALIDATION_FAILED로 거부된다")
    void createDraft_rejectsMissingCategory() {
        // given
        DropDraftRequest request = new DropDraftRequest(
                null, null, null, categoryId + 10_000, null, null, null, null, null);

        // when & then
        assertThatThrownBy(() -> dropService.createDraft(sellerId, request))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("공개 직전 카테고리가 비활성화되면 거부되고 상태는 DRAFT로 남는다")
    void publish_rejectsInactiveCategory() {
        // given
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        DropDraftRequest full = fullRequest();
        DropDraftRequest request = new DropDraftRequest(full.name(), full.description(), full.imageUrls(),
                categoryId, start, start.plusDays(1), new ShippingRequest(3000L, "안내"),
                full.optionGroups(), full.options());
        Long dropId = dropService.createDraft(sellerId, request).getId();
        entityManager.flush();
        jdbcTemplate.update("UPDATE categories SET is_active = false WHERE id = ?", categoryId);
        entityManager.clear();

        // when & then
        assertThatThrownBy(() -> dropService.publish(sellerId, dropId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED));

        entityManager.clear();
        Drop found = dropRepository.findById(dropId).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(DropStatus.DRAFT);
    }

    @Test
    @DisplayName("다른 판매자의 DROP은 목록에 나오지 않는다")
    void findSellerDrops_excludesOtherSeller() {
        // given
        Long myDropId = insertDrop(sellerId, "DRAFT");
        insertDrop(insertSeller(), "DRAFT");

        // when
        PageResponse<SellerDropListResponse> result = dropService.findSellerDrops(sellerId, null, 0, 20);

        // then
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content()).extracting(SellerDropListResponse::dropId).containsExactly(myDropId);
    }

    @Test
    @DisplayName("status 필터는 해당 상태의 DROP만 반환한다")
    void findSellerDrops_filtersByStatus() {
        // given
        insertDrop(sellerId, "DRAFT");
        insertCanceledDrop(sellerId);

        // when
        PageResponse<SellerDropListResponse> result =
                dropService.findSellerDrops(sellerId, DropStatus.DRAFT, 0, 20);

        // then
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content()).extracting(SellerDropListResponse::status).containsOnly(DropStatus.DRAFT);
    }

    @Test
    @DisplayName("페이지네이션 메타데이터가 계산된다")
    void findSellerDrops_paginates() {
        // given
        insertDrop(sellerId, "DRAFT");
        insertDrop(sellerId, "DRAFT");
        insertDrop(sellerId, "DRAFT");

        // when
        PageResponse<SellerDropListResponse> first = dropService.findSellerDrops(sellerId, null, 0, 2);

        // then
        assertThat(first.content()).hasSize(2);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();
    }

    @Test
    @DisplayName("minPrice는 활성 SKU 중 최저가이고, 활성 SKU가 없으면 null")
    void findSellerDrops_calculatesMinPrice() {
        // given
        Long withOptions = insertDrop(sellerId, "DRAFT");
        insertOption(withOptions, 1000L, 5, true);
        insertOption(withOptions, 500L, 0, true);
        insertOption(withOptions, 100L, 5, false);
        Long withoutOptions = insertDrop(sellerId, "DRAFT");

        // when
        PageResponse<SellerDropListResponse> result = dropService.findSellerDrops(sellerId, null, 0, 20);

        // then
        assertThat(minPriceOf(result, withOptions)).isEqualTo(500L);
        assertThat(minPriceOf(result, withoutOptions)).isNull();
    }

    @Test
    @DisplayName("상세 조회는 지연 로딩 상태에서도 그룹·값·SKU 매핑을 순서대로 반환한다")
    void findSellerDrop_returnsOrderedDetail() {
        // given
        Long dropId = dropService.createDraft(sellerId, fullRequest()).getId();
        entityManager.flush();
        entityManager.clear();

        // when
        SellerDropDetailResponse detail = dropService.findSellerDrop(sellerId, dropId);

        // then
        assertThat(detail.imageUrls())
                .containsExactly("https://example.com/a.jpg", "https://example.com/b.jpg");
        assertThat(detail.optionGroups()).extracting(SellerDropDetailResponse.OptionGroup::name)
                .containsExactly("소재", "길이");
        assertThat(detail.optionGroups().get(0).values()).extracting(SellerDropDetailResponse.OptionValue::value)
                .containsExactly("코튼", "린넨");
        assertThat(detail.options()).hasSize(2);
        assertThat(detail.options().get(0).selections()).extracting(SellerDropDetailResponse.Selection::groupId)
                .containsExactly(detail.optionGroups().get(0).groupId(), detail.optionGroups().get(1).groupId());
        assertThat(detail.minPrice()).isEqualTo(129000L);
    }

    private Long minPriceOf(PageResponse<SellerDropListResponse> result, Long dropId) {
        return result.content().stream()
                .filter(item -> item.dropId().equals(dropId))
                .findFirst()
                .orElseThrow()
                .minPrice();
    }

    private Long insertSeller() {
        String uniqueValue = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, display_name)
                VALUES (?, 'encoded-password', '판매자')
                RETURNING id
                """,
                Long.class,
                uniqueValue + "@example.com"
        );
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, 'GRAB 판매자', ?)
                RETURNING id
                """,
                Long.class,
                userId,
                "seller-" + uniqueValue + "@example.com"
        );
    }

    private Long insertDrop(Long ownerSellerId, String status) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id, status) VALUES (?, ?) RETURNING id",
                Long.class,
                ownerSellerId,
                status);
    }

    private Long insertCanceledDrop(Long ownerSellerId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, status, closed_at, close_reason)
                VALUES (?, 'CANCELED', CURRENT_TIMESTAMP, 'SELLER_CANCELED')
                RETURNING id
                """,
                Long.class,
                ownerSellerId);
    }

    private void insertOption(Long dropId, long unitPrice, int totalQuantity, boolean active) {
        jdbcTemplate.update(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active)
                VALUES (?, ?, ?, ?)
                """,
                dropId,
                unitPrice,
                totalQuantity,
                active);
    }

    private DropDraftRequest fullRequest() {
        OptionGroupRequest material = new OptionGroupRequest("material", "소재", 0,
                List.of(new OptionValueRequest("cotton", "코튼", 0),
                        new OptionValueRequest("linen", "린넨", 1)));
        OptionGroupRequest length = new OptionGroupRequest("length", "길이", 1,
                List.of(new OptionValueRequest("short", "숏", 0),
                        new OptionValueRequest("long", "롱", 1)));

        OptionRequest first = new OptionRequest(
                List.of(new SelectionRequest("material", "cotton"), new SelectionRequest("length", "short")),
                129000L, 10, true, 0);
        OptionRequest second = new OptionRequest(
                List.of(new SelectionRequest("material", "linen"), new SelectionRequest("length", "long")),
                139000L, 5, false, 1);

        return new DropDraftRequest("상품", "설명",
                List.of("https://example.com/a.jpg", "https://example.com/b.jpg"),
                categoryId, null, null, null, List.of(material, length), List.of(first, second));
    }

    private DropDraftRequest emptyRequest() {
        return new DropDraftRequest(null, null, null, null, null, null, null, null, null);
    }
}
