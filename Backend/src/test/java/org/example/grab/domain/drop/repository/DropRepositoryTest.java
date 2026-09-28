package org.example.grab.domain.drop.repository;

import jakarta.persistence.EntityManager;
import org.example.grab.global.config.JpaConfig;
import org.example.grab.domain.drop.dto.PublicDropListProjection;
import org.example.grab.domain.drop.dto.PublicDropSort;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.entity.option.DropOption;
import org.example.grab.domain.drop.entity.option.DropOptionGroup;
import org.example.grab.domain.drop.entity.option.DropOptionValue;
import org.example.grab.domain.drop.entity.option.DropOptionValueMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(JpaConfig.class)
@Testcontainers
class DropRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DropRepository dropRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sellerId;
    private Long categoryId;
    private Long buyerId;

    @BeforeEach
    void setUp() {
        String uniqueValue = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname)
                VALUES (?, 'encoded-password', '판매자')
                RETURNING id
                """,
                Long.class,
                uniqueValue + "@example.com"
        );
        sellerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, 'GRAB 판매자', ?)
                RETURNING id
                """,
                Long.class,
                userId,
                "seller-" + uniqueValue + "@example.com"
        );
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '패션') RETURNING id",
                Long.class,
                "TEST-" + uniqueValue
        );
        buyerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname)
                VALUES (?, 'encoded-password', '구매자')
                RETURNING id
                """,
                Long.class,
                "buyer-" + uniqueValue + "@example.com"
        );
    }

    @Test
    @DisplayName("옵션 조합을 저장하고 SKU별 그룹·값 매핑을 조회한다")
    void savesAndLoadsOptionCombinations() {
        // given
        Drop drop = Drop.createDraft(sellerId);

        DropOptionGroup color = DropOptionGroup.create(drop, "색상", 0);
        DropOptionValue black = DropOptionValue.create(color, "블랙", 0);
        DropOptionValue white = DropOptionValue.create(color, "화이트", 1);
        color.addValue(black);
        color.addValue(white);
        drop.addOptionGroup(color);

        DropOptionGroup size = DropOptionGroup.create(drop, "사이즈", 1);
        DropOptionValue small = DropOptionValue.create(size, "S", 0);
        DropOptionValue medium = DropOptionValue.create(size, "M", 1);
        size.addValue(small);
        size.addValue(medium);
        drop.addOptionGroup(size);

        DropOption blackSmall = DropOption.create(drop, 10000L, 5, 0);
        blackSmall.addValueMap(DropOptionValueMap.create(blackSmall, black));
        blackSmall.addValueMap(DropOptionValueMap.create(blackSmall, small));
        drop.addOption(blackSmall);

        DropOption whiteMedium = DropOption.create(drop, 12000L, 3, 1);
        whiteMedium.addValueMap(DropOptionValueMap.create(whiteMedium, white));
        whiteMedium.addValueMap(DropOptionValueMap.create(whiteMedium, medium));
        drop.addOption(whiteMedium);

        // when
        Long dropId = dropRepository.save(drop).getId();
        entityManager.flush();
        entityManager.clear();

        // then
        Drop found = dropRepository.findById(dropId).orElseThrow();
        Map<Long, Map<String, String>> selectionsByOption = found.getOptions().stream()
                .collect(Collectors.toMap(
                        DropOption::getId,
                        option -> option.getValueMaps().stream()
                                .collect(Collectors.toMap(
                                        map -> map.getGroup().getName(),
                                        map -> map.getValue().getValue()))));

        assertThat(selectionsByOption).hasSize(2);
        assertThat(selectionsByOption.get(blackSmall.getId()))
                .containsExactlyInAnyOrderEntriesOf(Map.of("색상", "블랙", "사이즈", "S"));
        assertThat(selectionsByOption.get(whiteMedium.getId()))
                .containsExactlyInAnyOrderEntriesOf(Map.of("색상", "화이트", "사이즈", "M"));
    }

    @Test
    @DisplayName("옵션 없는 상품은 값 매핑 없는 기본 SKU 하나로 저장된다")
    void savesDefaultOptionWithoutValueMaps() {
        // given
        Drop drop = Drop.createDraft(sellerId);
        DropOption defaultOption = DropOption.create(drop, 5000L, 10, 0);
        drop.addOption(defaultOption);

        // when
        Long dropId = dropRepository.saveAndFlush(drop).getId();
        entityManager.clear();

        // then
        Drop found = dropRepository.findById(dropId).orElseThrow();
        assertThat(found.getOptions()).hasSize(1);
        assertThat(found.getOptions().get(0).getValueMaps()).isEmpty();
    }

    @Test
    @DisplayName("같은 SKU에 같은 그룹을 두 번 매핑하면 저장에 실패한다")
    void failsToSaveDuplicateGroupMapping() {
        // given
        Drop drop = Drop.createDraft(sellerId);

        DropOptionGroup color = DropOptionGroup.create(drop, "색상", 0);
        DropOptionValue black = DropOptionValue.create(color, "블랙", 0);
        DropOptionValue white = DropOptionValue.create(color, "화이트", 1);
        color.addValue(black);
        color.addValue(white);
        drop.addOptionGroup(color);

        DropOption option = DropOption.create(drop, 10000L, 5, 0);
        option.addValueMap(DropOptionValueMap.create(option, black));
        option.addValueMap(DropOptionValueMap.create(option, white));
        drop.addOption(option);

        // when & then
        assertThatThrownBy(() -> dropRepository.saveAndFlush(drop))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("공개 상태만 반환하고 계산 필드(minPrice·soldOut·wishCount·thumbnail)·타입을 매핑한다")
    void findsPublicDropsWithComputedFields() {
        // given
        Long wish = insertPublishedDrop(DropStatus.WISH, "위시 상품");
        Long grab = insertPublishedDrop(DropStatus.GRAB, "그랩 상품");
        Long ended = insertPublishedDrop(DropStatus.ENDED, "종료 상품");
        insertDraftDrop();
        insertCanceledDrop();

        insertOption(wish, 20000L, 5, 0, 0, 0, true, 0);
        insertOption(wish, 10000L, 5, 0, 0, 0, false, 1);
        insertImage(wish, "https://img/2.jpg", 2);
        insertImage(wish, "https://img/1.jpg", 0);
        insertOption(grab, 30000L, 5, 5, 0, 0, true, 0);
        insertWish(buyerId, wish, false);
        insertWish(insertBuyer(), wish, false);
        insertWish(insertBuyer(), wish, true);

        // when
        Page<PublicDropListProjection> page = dropRepository.findPublicDrops(
                List.of("WISH", "GRAB", "ENDED"), null, null, null, PageRequest.of(0, 20));

        // then
        assertThat(page.getTotalElements()).isEqualTo(3);
        PublicDropListProjection wishRow = rowOf(page, wish);
        assertThat(wishRow.getStatus()).isEqualTo("WISH");
        assertThat(wishRow.getName()).isEqualTo("위시 상품");
        assertThat(wishRow.getMinPrice()).isEqualTo(20000L);
        assertThat(wishRow.getSoldOut()).isFalse();
        assertThat(wishRow.getWishCount()).isEqualTo(2L);
        assertThat(wishRow.getThumbnailUrl()).isEqualTo("https://img/1.jpg");
        assertThat(wishRow.getCategoryId()).isEqualTo(categoryId);
        assertThat(wishRow.getCategoryName()).isEqualTo("패션");
        assertThat(wishRow.getSaleStartsAt()).isNotNull();
        assertThat(wishRow.getSaleEndsAt()).isNotNull();

        // 활성 옵션 가용 재고 0, 옵션 없음은 모두 품절
        assertThat(rowOf(page, grab).getSoldOut()).isTrue();
        assertThat(rowOf(page, ended).getSoldOut()).isTrue();
    }

    @Test
    @DisplayName("categoryId·soldOut 필터와 keyword 부분 일치·대소문자 무시·이스케이프가 동작한다")
    void filtersByCategoryKeywordAndSoldOut() {
        // given
        Long percent = insertPublishedDrop(DropStatus.WISH, "스니커즈 100%");
        Long underscore = insertPublishedDrop(DropStatus.WISH, "스니커즈_프로");
        Long english = insertPublishedDrop(DropStatus.WISH, "SNEAKERS LIMITED");
        insertOption(percent, 1000L, 1, 0, 0, 0, true, 0);
        insertOption(underscore, 2000L, 1, 0, 0, 0, true, 0);
        insertOption(english, 3000L, 1, 1, 0, 0, true, 0);

        // when & then
        assertThat(dropRepository.findPublicDrops(List.of("WISH"), categoryId, null, null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(3);
        assertThat(idsOf(dropRepository.findPublicDrops(
                List.of("WISH"), null, "%100\\%%", null, PageRequest.of(0, 20))))
                .containsExactly(percent);
        assertThat(idsOf(dropRepository.findPublicDrops(
                List.of("WISH"), null, "%스니커즈\\_%", null, PageRequest.of(0, 20))))
                .containsExactly(underscore);
        assertThat(idsOf(dropRepository.findPublicDrops(
                List.of("WISH"), null, "%sneakers%", null, PageRequest.of(0, 20))))
                .containsExactly(english);
        assertThat(idsOf(dropRepository.findPublicDrops(
                List.of("WISH"), null, null, true, PageRequest.of(0, 20))))
                .containsExactly(english);
        assertThat(idsOf(dropRepository.findPublicDrops(
                List.of("WISH"), null, null, false, PageRequest.of(0, 20))))
                .containsExactlyInAnyOrder(percent, underscore);
    }

    @Test
    @DisplayName("페이지네이션이 totalElements·totalPages·hasNext를 계산한다")
    void paginatesPublicDrops() {
        // given
        insertPublishedDrop(DropStatus.WISH, "A");
        insertPublishedDrop(DropStatus.GRAB, "B");
        insertPublishedDrop(DropStatus.ENDED, "C");

        // when
        Page<PublicDropListProjection> first = dropRepository.findPublicDrops(
                List.of("WISH", "GRAB", "ENDED"), null, null, null, PageRequest.of(0, 2));

        // then
        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();
    }

    @Test
    @DisplayName("Pageable Sort가 컬럼명으로 적용되고 값이 같으면 id 내림차순으로 정렬한다")
    void appliesSortWithIdTieBreaker() {
        // given
        Long older = insertPublishedDrop(DropStatus.WISH, "먼저");
        Long newer = insertPublishedDrop(DropStatus.WISH, "나중");
        jdbcTemplate.update(
                "UPDATE drops SET sale_starts_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE id = ?", newer);

        // when
        List<Long> ascending = idsOf(dropRepository.findPublicDrops(
                List.of("WISH"), null, null, null,
                PageRequest.of(0, 20, PublicDropSort.parse("saleStartsAt,asc").toSort())));
        List<Long> descending = idsOf(dropRepository.findPublicDrops(
                List.of("WISH"), null, null, null,
                PageRequest.of(0, 20, PublicDropSort.parse("saleStartsAt,desc").toSort())));
        List<Long> defaultOrder = idsOf(dropRepository.findPublicDrops(
                List.of("WISH"), null, null, null,
                PageRequest.of(0, 20, PublicDropSort.parse(null).toSort())));

        // then: 값이 다르면 sale_starts_at 기준, 같으면(같은 트랜잭션 시각) id 내림차순
        assertThat(ascending).containsExactly(older, newer);
        assertThat(descending).containsExactly(newer, older);
        assertThat(defaultOrder).containsExactly(newer, older);
    }

    private Long insertPublishedDrop(DropStatus status, String name) {
        boolean grab = status == DropStatus.GRAB || status == DropStatus.ENDED;
        boolean ended = status == DropStatus.ENDED;
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description,
                                   shipping_fee, shipping_notice, sale_starts_at, sale_ends_at,
                                   published_at, grab_started_at, closed_at, close_reason)
                VALUES (?, ?, ?, ?, '설명', 3000, '안내',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        CURRENT_TIMESTAMP,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CASE WHEN ? THEN 'TIME_EXPIRED' ELSE NULL END)
                RETURNING id
                """,
                Long.class, sellerId, categoryId, status.name(), name, grab, ended, ended);
    }

    private void insertDraftDrop() {
        jdbcTemplate.update("INSERT INTO drops (seller_id, status) VALUES (?, 'DRAFT')", sellerId);
    }

    private void insertCanceledDrop() {
        jdbcTemplate.update(
                """
                INSERT INTO drops (seller_id, status, closed_at, close_reason)
                VALUES (?, 'CANCELED', CURRENT_TIMESTAMP, 'SELLER_CANCELED')
                """,
                sellerId);
    }

    private void insertOption(Long dropId, long unitPrice, int total, int reserved, int sold,
                              int withheld, boolean active, int sortOrder) {
        jdbcTemplate.update(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity,
                                          sold_quantity, withheld_quantity, is_active, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                dropId, unitPrice, total, reserved, sold, withheld, active, sortOrder);
    }

    private void insertImage(Long dropId, String imageUrl, int sortOrder) {
        jdbcTemplate.update(
                "INSERT INTO drop_images (drop_id, image_url, sort_order, alt_text) VALUES (?, ?, ?, '상품')",
                dropId, imageUrl, sortOrder);
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

    private Long insertBuyer() {
        String uniqueValue = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded-password', ?) RETURNING id",
                Long.class,
                "buyer-" + uniqueValue + "@example.com",
                "구매자-" + uniqueValue);
    }

    private PublicDropListProjection rowOf(Page<PublicDropListProjection> page, Long dropId) {
        return page.getContent().stream()
                .filter(projection -> projection.getDropId().equals(dropId))
                .findFirst()
                .orElseThrow();
    }

    private List<Long> idsOf(Page<PublicDropListProjection> page) {
        return page.getContent().stream().map(PublicDropListProjection::getDropId).toList();
    }
}
