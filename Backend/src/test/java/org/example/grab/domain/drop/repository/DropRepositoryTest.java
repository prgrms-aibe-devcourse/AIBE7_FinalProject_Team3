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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    private static final String PRESALE = "PRESALE_10M";

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
                """
                UPDATE drops
                SET sale_starts_at = CURRENT_TIMESTAMP + INTERVAL '1 hour',
                    sale_ends_at = CURRENT_TIMESTAMP + INTERVAL '2 hours'
                WHERE id = ?
                """, newer);

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

    @Test
    @DisplayName("인기 WISH는 활성 WISH 수 내림차순이고 취소 WISH는 세지 않으며 0건도 포함한다")
    void ordersPopularWishByActiveWishCount() {
        // given
        Long two = insertUpcomingWishDrop("둘", 30);
        Long one = insertUpcomingWishDrop("하나", 20);
        Long zero = insertUpcomingWishDrop("영", 10);
        insertWish(insertBuyer(), two, false);
        insertWish(insertBuyer(), two, false);
        insertWish(insertBuyer(), one, false);
        insertWish(insertBuyer(), one, true);
        insertWish(insertBuyer(), one, true);
        insertWish(insertBuyer(), zero, true);

        // when
        Page<PublicDropListProjection> page = dropRepository.findPopularWishDrops(
                null, null, null, OffsetDateTime.now(ZoneOffset.UTC), PageRequest.of(0, 20));

        // then
        assertThat(idsOf(page)).containsExactly(two, one, zero);
        assertThat(rowOf(page, two).getWishCount()).isEqualTo(2L);
        assertThat(rowOf(page, one).getWishCount()).isEqualTo(1L);
        assertThat(rowOf(page, zero).getWishCount()).isZero();
    }

    @Test
    @DisplayName("인기 WISH 동률은 published_at 내림차순, 그다음 id 내림차순이다")
    void breaksPopularWishTiesByPublishedAtThenId() {
        // given: 모두 WISH 0건
        Long oldest = insertUpcomingWishDrop("오래됨", 60);
        Long sameTimeFirst = insertUpcomingWishDrop("같은 시각 1", 10);
        Long sameTimeSecond = insertUpcomingWishDrop("같은 시각 2", 10);

        // when
        List<Long> ids = idsOf(dropRepository.findPopularWishDrops(
                null, null, null, OffsetDateTime.now(ZoneOffset.UTC), PageRequest.of(0, 20)));

        // then
        assertThat(ids).containsExactly(sameTimeSecond, sameTimeFirst, oldest);
    }

    @Test
    @DisplayName("인기 WISH는 저장 상태 WISH이면서 판매 시작 전인 상품만 후보로 삼는다")
    void popularWishIncludesOnlyWishBeforeSaleStart() {
        // given
        Long upcoming = insertUpcomingWishDrop("판매 전", 10);
        Long batchDelayed = insertDrop(DropStatus.WISH, -1, 1);
        Long grab = insertDrop(DropStatus.GRAB, -1, 1);
        Long ended = insertDrop(DropStatus.ENDED, -2, -1);
        insertDraftDrop();
        insertCanceledDrop();
        for (Long dropId : List.of(batchDelayed, grab, ended)) {
            insertWish(insertBuyer(), dropId, false);
        }

        // when
        Page<PublicDropListProjection> page = dropRepository.findPopularWishDrops(
                null, null, null, OffsetDateTime.now(ZoneOffset.UTC), PageRequest.of(0, 20));

        // then
        assertThat(idsOf(page)).containsExactly(upcoming);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("판매 시작 시각과 now가 같으면 인기 WISH 후보에서 빠진다")
    void popularWishExcludesDropStartingExactlyNow() {
        // given
        Long upcoming = insertUpcomingWishDrop("판매 전", 10);
        OffsetDateTime now = jdbcTemplate.queryForObject(
                "SELECT sale_starts_at FROM drops WHERE id = ?", OffsetDateTime.class, upcoming);

        // when
        Page<PublicDropListProjection> page = dropRepository.findPopularWishDrops(
                null, null, null, now, PageRequest.of(0, 20));

        // then
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    @DisplayName("인기 WISH는 전체 후보를 정렬한 뒤 페이지를 잘라 페이지가 달라도 전역 순위를 유지한다")
    void paginatesPopularWishAfterGlobalOrdering() {
        // given: id·공개 시각이 앞선 상품일수록 WISH가 많아 기본 정렬과 순서가 반대다
        Long first = insertUpcomingWishDrop("1위", 50);
        Long second = insertUpcomingWishDrop("2위", 40);
        Long third = insertUpcomingWishDrop("3위", 30);
        Long fourth = insertUpcomingWishDrop("4위", 20);
        insertWishes(first, 4);
        insertWishes(second, 3);
        insertWishes(third, 2);
        insertWishes(fourth, 1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        Page<PublicDropListProjection> firstPage = dropRepository.findPopularWishDrops(
                null, null, null, now, PageRequest.of(0, 2));
        Page<PublicDropListProjection> secondPage = dropRepository.findPopularWishDrops(
                null, null, null, now, PageRequest.of(1, 2));

        // then
        assertThat(idsOf(firstPage)).containsExactly(first, second);
        assertThat(idsOf(secondPage)).containsExactly(third, fourth);
        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    @DisplayName("인기 WISH에도 categoryId·keyword·soldOut 필터가 그대로 적용된다")
    void appliesFiltersToPopularWish() {
        // given
        Long inStock = insertUpcomingWishDrop("스니커즈 재고", 20);
        Long soldOut = insertUpcomingWishDrop("스니커즈 품절", 10);
        Long otherName = insertUpcomingWishDrop("후드티", 5);
        insertOption(inStock, 1000L, 1, 0, 0, 0, true, 0);
        insertOption(soldOut, 1000L, 1, 1, 0, 0, true, 0);
        insertOption(otherName, 1000L, 1, 0, 0, 0, true, 0);
        Long otherCategory = jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '잡화') RETURNING id",
                Long.class, "TEST-" + UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when & then
        assertThat(idsOf(dropRepository.findPopularWishDrops(
                null, "%스니커즈%", null, now, PageRequest.of(0, 20))))
                .containsExactly(soldOut, inStock);
        assertThat(idsOf(dropRepository.findPopularWishDrops(
                null, null, true, now, PageRequest.of(0, 20))))
                .containsExactly(soldOut);
        assertThat(idsOf(dropRepository.findPopularWishDrops(
                null, null, false, now, PageRequest.of(0, 20))))
                .containsExactly(otherName, inStock);
        assertThat(dropRepository.findPopularWishDrops(
                otherCategory, null, null, now, PageRequest.of(0, 20)).getTotalElements())
                .isZero();
    }

    @Test
    @DisplayName("인기 GRAB은 결제 확정·미취소 주문 수량 합 내림차순이고 미결제·만료·취소 주문은 세지 않으며 0건도 포함한다")
    void ordersPopularGrabByPaidQuantity() {
        // given
        Long multi = insertSellingDrop(DropStatus.GRAB, "다중 옵션", 30);
        Long single = insertSellingDrop(DropStatus.GRAB, "단일 옵션", 20);
        Long none = insertSellingDrop(DropStatus.GRAB, "판매 없음", 10);
        Long multiFirst = insertOptionReturningId(multi, 10, 0);
        Long multiSecond = insertOptionReturningId(multi, 10, 1);
        Long singleOption = insertOptionReturningId(single, 10, 0);
        Long noneOption = insertOptionReturningId(none, 10, 0);

        // multi: 결제 주문 2건(2+1, 1) = 4
        Long paidTwoItems = insertOrder(multi, "PAID");
        insertOrderItem(paidTwoItems, multi, multiFirst, 2);
        insertOrderItem(paidTwoItems, multi, multiSecond, 1);
        insertOrderItem(insertOrder(multi, "DELIVERED"), multi, multiFirst, 1);
        insertOrderItem(insertOrder(multi, "PAYMENT_PENDING"), multi, multiFirst, 9);
        insertOrderItem(insertOrder(multi, "EXPIRED"), multi, multiSecond, 9);
        // single: 결제 3, 결제 후 취소 9는 제외 = 3
        insertOrderItem(insertOrder(single, "PAID"), single, singleOption, 3);
        insertOrderItem(insertPaidThenCanceledOrder(single), single, singleOption, 9);
        // none: 미결제 취소만 있음 = 0
        insertOrderItem(insertOrder(none, "CANCELED"), none, noneOption, 9);
        insertWishes(multi, 2);

        // when
        Page<PublicDropListProjection> page = dropRepository.findPopularGrabDrops(
                null, null, null, OffsetDateTime.now(ZoneOffset.UTC), PageRequest.of(0, 20));

        // then: 다중 옵션·다중 주문이 행을 늘리거나 다른 집계를 부풀리지 않는다
        assertThat(idsOf(page)).containsExactly(multi, single, none);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(rowOf(page, multi).getWishCount()).isEqualTo(2L);
        assertThat(rowOf(page, multi).getMinPrice()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("인기 GRAB 동률은 published_at 내림차순, 그다음 id 내림차순이다")
    void breaksPopularGrabTiesByPublishedAtThenId() {
        // given: 모두 판매 0건
        Long oldest = insertSellingDrop(DropStatus.GRAB, "오래됨", 60);
        Long sameTimeFirst = insertSellingDrop(DropStatus.GRAB, "같은 시각 1", 10);
        Long sameTimeSecond = insertSellingDrop(DropStatus.GRAB, "같은 시각 2", 10);
        for (Long dropId : List.of(oldest, sameTimeFirst, sameTimeSecond)) {
            insertOptionReturningId(dropId, 1, 0);
        }

        // when
        List<Long> ids = idsOf(dropRepository.findPopularGrabDrops(
                null, null, null, OffsetDateTime.now(ZoneOffset.UTC), PageRequest.of(0, 20)));

        // then
        assertThat(ids).containsExactly(sameTimeSecond, sameTimeFirst, oldest);
    }

    @Test
    @DisplayName("인기 GRAB은 판매 기간 안이고 활성 옵션 가용 재고가 있는 WISH·GRAB 저장 상태만 후보로 삼는다")
    void popularGrabIncludesOnlyPurchasableDrops() {
        // given
        Long grab = insertSellingDrop(DropStatus.GRAB, "판매 중", 10);
        Long startDelayed = insertSellingDrop(DropStatus.WISH, "시작 배치 지연", 10);
        Long noStock = insertSellingDrop(DropStatus.GRAB, "재고 소진", 10);
        Long inactiveStockOnly = insertSellingDrop(DropStatus.GRAB, "비활성 옵션만 재고", 10);
        insertSellingDrop(DropStatus.GRAB, "옵션 없음", 10);
        Long upcoming = insertUpcomingWishDrop("판매 전", 10);
        Long endDelayed = insertDrop(DropStatus.GRAB, -2, -1);
        Long ended = insertDrop(DropStatus.ENDED, -2, -1);
        insertDraftDrop();
        insertCanceledDrop();
        insertOptionReturningId(grab, 1, 0);
        insertOptionReturningId(startDelayed, 1, 0);
        insertOption(noStock, 1000L, 3, 1, 1, 1, true, 0);
        insertOption(inactiveStockOnly, 1000L, 5, 0, 0, 0, false, 0);
        insertOption(inactiveStockOnly, 1000L, 1, 1, 0, 0, true, 1);
        for (Long dropId : List.of(upcoming, endDelayed, ended)) {
            insertOptionReturningId(dropId, 1, 0);
        }

        // when
        Page<PublicDropListProjection> page = dropRepository.findPopularGrabDrops(
                null, null, null, OffsetDateTime.now(ZoneOffset.UTC), PageRequest.of(0, 20));

        // then: 응답 status는 저장 상태를 유지한다
        assertThat(idsOf(page)).containsExactlyInAnyOrder(grab, startDelayed);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(rowOf(page, startDelayed).getStatus()).isEqualTo("WISH");
        assertThat(rowOf(page, startDelayed).getSoldOut()).isFalse();
    }

    @Test
    @DisplayName("인기 GRAB은 now가 판매 시작 시각이면 포함하고 판매 종료 시각이면 제외한다")
    void popularGrabSalePeriodBoundaries() {
        // given
        Long dropId = insertSellingDrop(DropStatus.GRAB, "경계", 10);
        insertOptionReturningId(dropId, 1, 0);
        OffsetDateTime startsAt = jdbcTemplate.queryForObject(
                "SELECT sale_starts_at FROM drops WHERE id = ?", OffsetDateTime.class, dropId);
        OffsetDateTime endsAt = jdbcTemplate.queryForObject(
                "SELECT sale_ends_at FROM drops WHERE id = ?", OffsetDateTime.class, dropId);

        // when & then
        assertThat(idsOf(dropRepository.findPopularGrabDrops(
                null, null, null, startsAt, PageRequest.of(0, 20))))
                .containsExactly(dropId);
        assertThat(dropRepository.findPopularGrabDrops(
                null, null, null, endsAt, PageRequest.of(0, 20)).getContent())
                .isEmpty();
    }

    @Test
    @DisplayName("인기 GRAB은 전체 후보를 정렬한 뒤 페이지를 잘라 페이지가 달라도 전역 순위를 유지한다")
    void paginatesPopularGrabAfterGlobalOrdering() {
        // given: id·공개 시각이 앞선 상품일수록 많이 팔려 기본 정렬과 순서가 반대다
        Long first = insertSellingDrop(DropStatus.GRAB, "1위", 50);
        Long second = insertSellingDrop(DropStatus.GRAB, "2위", 40);
        Long third = insertSellingDrop(DropStatus.GRAB, "3위", 30);
        Long fourth = insertSellingDrop(DropStatus.GRAB, "4위", 20);
        insertPaidQuantity(first, 4);
        insertPaidQuantity(second, 3);
        insertPaidQuantity(third, 2);
        insertPaidQuantity(fourth, 1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        Page<PublicDropListProjection> firstPage = dropRepository.findPopularGrabDrops(
                null, null, null, now, PageRequest.of(0, 2));
        Page<PublicDropListProjection> secondPage = dropRepository.findPopularGrabDrops(
                null, null, null, now, PageRequest.of(1, 2));

        // then
        assertThat(idsOf(firstPage)).containsExactly(first, second);
        assertThat(idsOf(secondPage)).containsExactly(third, fourth);
        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    @DisplayName("인기 GRAB에 soldOut=true를 주면 빈 목록이고 soldOut=false·keyword·categoryId 필터는 그대로 적용된다")
    void appliesFiltersToPopularGrab() {
        // given
        Long sneakers = insertSellingDrop(DropStatus.GRAB, "스니커즈", 20);
        Long hoodie = insertSellingDrop(DropStatus.GRAB, "후드티", 10);
        insertOptionReturningId(sneakers, 1, 0);
        insertOptionReturningId(hoodie, 1, 0);
        Long otherCategory = jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '잡화') RETURNING id",
                Long.class, "TEST-" + UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when & then
        Page<PublicDropListProjection> soldOut = dropRepository.findPopularGrabDrops(
                null, null, true, now, PageRequest.of(0, 20));
        assertThat(soldOut.getContent()).isEmpty();
        assertThat(soldOut.getTotalElements()).isZero();
        assertThat(idsOf(dropRepository.findPopularGrabDrops(
                null, null, false, now, PageRequest.of(0, 20))))
                .containsExactly(hoodie, sneakers);
        assertThat(idsOf(dropRepository.findPopularGrabDrops(
                null, "%스니커%", null, now, PageRequest.of(0, 20))))
                .containsExactly(sneakers);
        assertThat(dropRepository.findPopularGrabDrops(
                otherCategory, null, null, now, PageRequest.of(0, 20)).getTotalElements())
                .isZero();
    }

    @Test
    @DisplayName("시작 배치가 시작 시각이 지난 WISH만 GRAB으로 전환하고 전환 시각을 기록한다")
    void startGrabBatchTransitionsDueWishOnly() {
        // given
        Long started = insertDrop(DropStatus.WISH, -1, 1);
        Long notStarted = insertDrop(DropStatus.WISH, 1, 2);
        Long alreadyGrab = insertDrop(DropStatus.GRAB, -2, -1);
        Long canceled = insertDrop(DropStatus.CANCELED, -1, 1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        List<Long> startedIds = dropRepository.startGrabBatch(now, 500);
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(startedIds).containsExactly(started);
        Map<String, Object> row = dropRow(started);
        assertThat(row.get("status")).isEqualTo("GRAB");
        assertThat(row.get("grab_started_at")).isNotNull();
        assertThat(row.get("sale_starts_at")).isNotNull();
        assertThat(dropRow(notStarted).get("status")).isEqualTo("WISH");
        assertThat(dropRow(alreadyGrab).get("status")).isEqualTo("GRAB");
        assertThat(dropRow(canceled).get("status")).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("같은 시작 배치를 두 번 실행하면 두 번째는 전환 대상이 없다")
    void startGrabBatchIsIdempotent() {
        // given
        insertDrop(DropStatus.WISH, -1, 1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        List<Long> first = dropRepository.startGrabBatch(now, 500);
        List<Long> second = dropRepository.startGrabBatch(now, 500);

        // then
        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("시작 배치가 batchSize만큼만 전환하고 같은 시각이면 id 오름차순으로 선점한다")
    void startGrabBatchLimitsBatchSizeAndOrdersById() {
        // given
        Long first = insertDropWithSameStart(DropStatus.WISH);
        Long second = insertDropWithSameStart(DropStatus.WISH);
        Long third = insertDropWithSameStart(DropStatus.WISH);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        List<Long> startedIds = dropRepository.startGrabBatch(now, 2);
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(startedIds).containsExactly(first, second);
        assertThat(dropRow(first).get("status")).isEqualTo("GRAB");
        assertThat(dropRow(second).get("status")).isEqualTo("GRAB");
        assertThat(dropRow(third).get("status")).isEqualTo("WISH");
    }

    @Test
    @DisplayName("종료 배치가 종료 시각이 지난 GRAB만 ENDED·TIME_EXPIRED로 전환한다")
    void endGrabBatchTransitionsDueGrabOnly() {
        // given
        Long ended = insertDrop(DropStatus.GRAB, -2, -1);
        Long stillOnSale = insertDrop(DropStatus.GRAB, -1, 1);
        Long wish = insertDrop(DropStatus.WISH, -1, 1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        int updated = dropRepository.endGrabBatch(now, 500);
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(updated).isEqualTo(1);
        Map<String, Object> row = dropRow(ended);
        assertThat(row.get("status")).isEqualTo("ENDED");
        assertThat(row.get("closed_at")).isNotNull();
        assertThat(row.get("close_reason")).isEqualTo("TIME_EXPIRED");
        assertThat(dropRow(stillOnSale).get("status")).isEqualTo("GRAB");
        assertThat(dropRow(wish).get("status")).isEqualTo("WISH");
    }

    @Test
    @DisplayName("종료 배치는 같은 배치를 두 번 실행해도 중복 전환하지 않는다")
    void endGrabBatchIsIdempotent() {
        // given
        insertDrop(DropStatus.GRAB, -2, -1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        int first = dropRepository.endGrabBatch(now, 500);
        int second = dropRepository.endGrabBatch(now, 500);

        // then
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    @Test
    @DisplayName("임박 알림 배치가 아직 시작하지 않고 10분 안에 시작하는 WISH만 선점한다")
    void claimNoticeBatchSelectsDropsStartingWithinLead() {
        // given
        Long startsSoon = insertDropStartingInMinutes(5);
        Long startsLater = insertDropStartingInMinutes(30);
        Long alreadyStarted = insertDrop(DropStatus.WISH, -1, 1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        List<Long> claimed = dropRepository.claimNoticeBatch(PRESALE, now, now.plusMinutes(10), 500);

        // then
        assertThat(claimed).containsExactly(startsSoon);
        assertThat(noticeCount(startsSoon)).isEqualTo(1);
        assertThat(noticeCount(startsLater)).isZero();
        assertThat(noticeCount(alreadyStarted)).isZero();
    }

    @Test
    @DisplayName("같은 임박 알림 배치를 두 번 실행하면 두 번째는 선점 대상이 없고 기록도 늘지 않는다")
    // 상태가 바뀌지 않는 알림이라 유니크 제약만이 중복 발송을 막는다(GR-69). 그 보증을 확인하는 테스트
    void claimNoticeBatchIsIdempotent() {
        // given
        Long startsSoon = insertDropStartingInMinutes(5);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        List<Long> first = dropRepository.claimNoticeBatch(PRESALE, now, now.plusMinutes(10), 500);
        List<Long> second = dropRepository.claimNoticeBatch(PRESALE, now, now.plusMinutes(10), 500);

        // then
        assertThat(first).containsExactly(startsSoon);
        assertThat(second).isEmpty();
        assertThat(noticeCount(startsSoon)).isEqualTo(1);
    }

    @Test
    @DisplayName("임박 알림 배치가 batchSize만큼만 선점하고 시작이 빠른 순으로 집는다")
    void claimNoticeBatchLimitsBatchSize() {
        // given
        Long first = insertDropStartingInMinutes(1);
        Long second = insertDropStartingInMinutes(2);
        insertDropStartingInMinutes(3);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        List<Long> claimed = dropRepository.claimNoticeBatch(PRESALE, now, now.plusMinutes(10), 2);

        // then
        assertThat(claimed).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("이미 선점된 DROP이 batchSize를 채워도 아직 선점되지 않은 DROP을 집는다")
    /*
     * LIMIT이 ON CONFLICT보다 먼저 적용되므로, 후보를 거르지 않으면 batchSize만큼의 기선점 DROP이
     * 매 주기 앞자리를 차지해 그 뒤 DROP은 영영 선점되지 않는다. 배치 반복도 0건을 받고 멈춘다(GR-69).
     */
    void claimNoticeBatchSkipsAlreadyClaimedDrops() {
        // given: 먼저 선점된 DROP 2건과, 그보다 늦게 시작하는 아직 선점되지 않은 DROP 1건
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertDropStartingInMinutes(1);
        insertDropStartingInMinutes(2);
        dropRepository.claimNoticeBatch(PRESALE, now, now.plusMinutes(10), 2);
        Long notClaimed = insertDropStartingInMinutes(3);

        // when: 앞의 2건이 batchSize를 채우는 크기로 다시 실행한다
        List<Long> claimed = dropRepository.claimNoticeBatch(PRESALE, now, now.plusMinutes(10), 2);

        // then: 기선점 DROP은 후보에서 빠지고 남은 DROP이 집힌다
        assertThat(claimed).containsExactly(notClaimed);
    }

    private int noticeCount(Long dropId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM drop_notifications WHERE drop_id = ? AND type = ?",
                Integer.class, dropId, PRESALE);
    }

    private Long insertDropStartingInMinutes(long minutes) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,"
                        + " sale_starts_at, sale_ends_at, published_at)"
                        + " VALUES (?, ?, 'WISH', ?, '설명', 3000, '안내',"
                        + " CURRENT_TIMESTAMP + (? || ' minutes')::interval,"
                        + " CURRENT_TIMESTAMP + INTERVAL '3 hours', CURRENT_TIMESTAMP) RETURNING id",
                Long.class, sellerId, categoryId, "임박-" + UUID.randomUUID(), minutes);
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

    private Long insertDropWithSameStart(DropStatus status) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee,
                                   shipping_notice, sale_starts_at, sale_ends_at, published_at)
                VALUES (?, ?, ?, ?, '설명', 3000, '안내', CURRENT_TIMESTAMP - INTERVAL '1 hour',
                        CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class, sellerId, categoryId, status.name(), "상품-" + status);
    }

    private Map<String, Object> dropRow(Long dropId) {
        return jdbcTemplate.queryForMap("SELECT * FROM drops WHERE id = ?", dropId);
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

    // 판매 시작 1시간 전인 WISH 상품. published_at은 minutesAgo분 전으로 둬 동률 정렬을 검증한다.
    private Long insertUpcomingWishDrop(String name, long minutesAgo) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description,
                                   shipping_fee, shipping_notice, sale_starts_at, sale_ends_at, published_at)
                VALUES (?, ?, 'WISH', ?, '설명', 3000, '안내',
                        CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP + INTERVAL '2 hours',
                        CURRENT_TIMESTAMP - (? || ' minutes')::interval)
                RETURNING id
                """,
                Long.class, sellerId, categoryId, name, minutesAgo);
    }

    private void insertWishes(Long dropId, int count) {
        for (int i = 0; i < count; i++) {
            insertWish(insertBuyer(), dropId, false);
        }
    }

    // 판매 기간(1시간 전 ~ 1시간 후) 안의 상품. 시작 배치가 늦은 경우를 위해 저장 상태 WISH도 받는다.
    private Long insertSellingDrop(DropStatus status, String name, long publishedMinutesAgo) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description,
                                   shipping_fee, shipping_notice, sale_starts_at, sale_ends_at,
                                   published_at, grab_started_at)
                VALUES (?, ?, ?, ?, '설명', 3000, '안내',
                        CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        CURRENT_TIMESTAMP - (? || ' minutes')::interval,
                        CASE WHEN ? = 'GRAB' THEN CURRENT_TIMESTAMP - INTERVAL '1 hour' ELSE NULL END)
                RETURNING id
                """,
                Long.class, sellerId, categoryId, status.name(), name, publishedMinutesAgo, status.name());
    }

    private Long insertOptionReturningId(Long dropId, int total, int sortOrder) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
                VALUES (?, 10000, ?, TRUE, ?)
                RETURNING id
                """,
                Long.class, dropId, total, sortOrder);
    }

    // 상태별 필수 시각(paid_at·canceled_at·expired_at)과 취소 사유를 제약에 맞게 채운다.
    private Long insertOrder(Long dropId, String status) {
        String suffix = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO orders (order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
                                    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
                                    total_amount, recipient_name, recipient_phone, postal_code, address_line1,
                                    payment_expires_at, paid_at, canceled_at, expired_at, cancel_reason)
                VALUES (?, ?, ?, ?, 'hash', ?, '상품', '브랜드', 1000, 0, 1000,
                        '수령인', '01000000000', '00000', '주소', CURRENT_TIMESTAMP + INTERVAL '10 minutes',
                        CASE WHEN ? IN ('PAID', 'PREPARING', 'SHIPPED', 'DELIVERED') THEN CURRENT_TIMESTAMP END,
                        CASE WHEN ? = 'CANCELED' THEN CURRENT_TIMESTAMP END,
                        CASE WHEN ? = 'EXPIRED' THEN CURRENT_TIMESTAMP END,
                        CASE WHEN ? = 'CANCELED' THEN '테스트 취소' END)
                RETURNING id
                """,
                Long.class, "ORDER-" + suffix, buyerId, dropId, suffix, status,
                status, status, status, status);
    }

    private Long insertPaidThenCanceledOrder(Long dropId) {
        Long orderId = insertOrder(dropId, "CANCELED");
        jdbcTemplate.update("UPDATE orders SET paid_at = CURRENT_TIMESTAMP WHERE id = ?", orderId);
        return orderId;
    }

    private void insertOrderItem(Long orderId, Long dropId, Long optionId, int quantity) {
        jdbcTemplate.update(
                """
                INSERT INTO order_items (order_id, drop_id, option_id, option_name_snapshot, unit_price, quantity)
                VALUES (?, ?, ?, '기본', 10000, ?)
                """,
                orderId, dropId, optionId, quantity);
    }

    private void insertPaidQuantity(Long dropId, int quantity) {
        Long optionId = insertOptionReturningId(dropId, 10, 0);
        insertOrderItem(insertOrder(dropId, "PAID"), dropId, optionId, quantity);
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
