package org.example.grab.domain.demand.repository;

import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.ConversionSample;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.InitialRecommendation;
import org.example.grab.domain.demand.dto.DemandOption;
import org.example.grab.domain.demand.dto.MarketSample;
import org.example.grab.domain.demand.repository.DemandAnalysisRepository.TargetDrop;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(DemandAnalysisRepository.class)
@Testcontainers
class DemandAnalysisRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DemandAnalysisRepository demandAnalysisRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sellerId;
    private Long otherSellerId;
    private Long categoryId;
    private Long otherCategoryId;
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-20T00:00:00Z");

    @BeforeEach
    void setUp() {
        sellerId = insertSeller();
        otherSellerId = insertSeller();
        categoryId = insertCategory();
        otherCategoryId = insertCategory();
    }

    @Test
    @DisplayName("분석 대상 DROP의 소유자·상태·카테고리·판매 시작 시각을 조회한다")
    void findTargetDrop_returnsOwnerAndSchedule() {
        // given
        Long dropId = insertDrop(sellerId, categoryId, "WISH", null, null);

        // when
        TargetDrop drop = demandAnalysisRepository.findTargetDrop(dropId).orElseThrow();

        // then
        assertThat(drop.sellerId()).isEqualTo(sellerId);
        assertThat(drop.status()).isEqualTo("WISH");
        assertThat(drop.categoryId()).isEqualTo(categoryId);
        assertThat(drop.saleStartsAt()).isEqualTo(now.plusDays(3));
        assertThat(demandAnalysisRepository.findTargetDrop(dropId + 1000)).isEmpty();
    }

    @Test
    @DisplayName("활성 WISH 수와 최근 등록일(KST)별 활성 WISH 수를 센다")
    void countWishes_countsActiveOnlyByKstDate() {
        // given: 2026-10-18T15:30Z는 KST로 10-19, 10-19T14:59Z는 KST로 10-19 23:59
        Long dropId = insertDrop(sellerId, categoryId, "WISH", null, null);
        insertWish(dropId, OffsetDateTime.parse("2026-10-18T15:30:00Z"), false);
        insertWish(dropId, OffsetDateTime.parse("2026-10-19T14:59:00Z"), false);
        insertWish(dropId, OffsetDateTime.parse("2026-10-19T15:00:00Z"), false);
        insertWish(dropId, OffsetDateTime.parse("2026-10-19T16:00:00Z"), true);
        insertWish(dropId, OffsetDateTime.parse("2026-10-01T00:00:00Z"), false);

        // when
        long activeCount = demandAnalysisRepository.countActiveWishes(dropId);
        Map<LocalDate, Long> daily = demandAnalysisRepository.countDailyActiveWishes(
                dropId, OffsetDateTime.parse("2026-10-06T15:00:00Z"));

        // then: 취소 WISH는 제외, 기간 이전 등록은 일별 집계에서만 제외
        assertThat(activeCount).isEqualTo(4);
        assertThat(daily).containsOnly(
                entry(LocalDate.parse("2026-10-19"), 2L),
                entry(LocalDate.parse("2026-10-20"), 1L));
    }

    @Test
    @DisplayName("활성 옵션만 sort_order 순으로, 표시명은 그룹 순서대로 값을 이어 조회한다")
    void findActiveOptions_buildsDisplayNames() {
        // given
        Long dropId = insertDrop(sellerId, categoryId, "WISH", null, null);
        Long size = insertGroup(dropId, "사이즈", 1);
        Long color = insertGroup(dropId, "색상", 0);
        Long black = insertValue(color, "블랙");
        Long medium = insertValue(size, "M");
        Long large = insertValue(size, "L");
        Long secondOption = insertOption(dropId, 30, 1, true);
        Long firstOption = insertOption(dropId, 40, 0, true);
        Long inactiveOption = insertOption(dropId, 10, 2, false);
        mapValue(firstOption, color, black, dropId);
        mapValue(firstOption, size, medium, dropId);
        mapValue(secondOption, color, black, dropId);
        mapValue(secondOption, size, large, dropId);
        mapValue(inactiveOption, size, medium, dropId);

        // when
        List<DemandOption> options = demandAnalysisRepository.findActiveOptions(dropId);

        // then
        assertThat(options).containsExactly(
                new DemandOption(firstOption, "블랙 / M", 40, 0),
                new DemandOption(secondOption, "블랙 / L", 30, 1));
    }

    @Test
    @DisplayName("값 매핑이 없는 기본 옵션의 표시명은 기본이다")
    void findActiveOptions_namesDefaultOption() {
        // given
        Long dropId = insertDrop(sellerId, categoryId, "WISH", null, null);
        Long optionId = insertOption(dropId, 50, 0, true);

        // when & then
        assertThat(demandAnalysisRepository.findActiveOptions(dropId))
                .containsExactly(new DemandOption(optionId, "기본", 50, 0));
    }

    @Test
    @DisplayName("내 실적 표본은 본인의 같은 카테고리 시간 만료 종료 DROP만 쓰고 활성 WISH 사용자의 확정 주문만 전환으로 센다")
    void findOwnConversionSample_filtersSamplesAndBuyers() {
        // given: 표본 DROP 2개
        Long target = insertDrop(sellerId, categoryId, "WISH", null, null);
        Long sampleA = insertDrop(sellerId, categoryId, "ENDED", "TIME_EXPIRED", now.minusDays(2));
        Long sampleB = insertDrop(sellerId, categoryId, "ENDED", "TIME_EXPIRED", now.minusDays(1));

        // 표본 A: WISH 3명 중 2명 구매(2개 + 1개). 취소 주문만 있는 1명은 미전환
        Long buyer1 = insertWishUser(sampleA, false);
        Long buyer2 = insertWishUser(sampleA, false);
        Long canceledBuyer = insertWishUser(sampleA, false);
        insertOrder(sampleA, buyer1, true, false, 2);
        insertOrder(sampleA, buyer2, true, false, 1);
        insertOrder(sampleA, canceledBuyer, true, true, 5);
        // 취소한 WISH 사용자의 구매와 WISH 없는 구매자는 세지 않는다
        Long canceledWisher = insertWishUser(sampleA, true);
        insertOrder(sampleA, canceledWisher, true, false, 3);
        insertOrder(sampleA, insertUser(), true, false, 4);

        // 표본 B: WISH 2명 중 1명이 주문 2건(1개 + 2개), 1명은 결제 대기 주문만 있음
        Long repeatBuyer = insertWishUser(sampleB, false);
        Long pendingBuyer = insertWishUser(sampleB, false);
        insertOrder(sampleB, repeatBuyer, true, false, 1);
        insertOrder(sampleB, repeatBuyer, true, false, 2);
        insertOrder(sampleB, pendingBuyer, false, false, 1);

        // 표본에서 빠지는 DROP: 다른 판매자, 다른 카테고리, 판매자 취소, 판매 중, 활성 WISH 없음
        insertWishUser(insertDrop(otherSellerId, categoryId, "ENDED", "TIME_EXPIRED", now), false);
        insertWishUser(insertDrop(sellerId, otherCategoryId, "ENDED", "TIME_EXPIRED", now), false);
        insertWishUser(insertDrop(sellerId, categoryId, "CANCELED", "SELLER_CANCELED", now), false);
        insertWishUser(insertDrop(sellerId, categoryId, "GRAB", null, null), false);
        insertWishUser(insertDrop(sellerId, categoryId, "ENDED", "TIME_EXPIRED", now), true);
        insertWishUser(target, false);

        // when
        ConversionSample sample = demandAnalysisRepository.findOwnConversionSample(sellerId, categoryId, target, 20);

        // then: WISH 5명(취소 WISH 제외), 전환 3명, 수량 2 + 1 + 3
        assertThat(sample).isEqualTo(new ConversionSample(2, 5, 3, 6));
    }

    @Test
    @DisplayName("내 실적 표본은 최근 종료순으로 최대 개수만 쓴다")
    void findOwnConversionSample_limitsToRecentDrops() {
        // given
        Long target = insertDrop(sellerId, categoryId, "WISH", null, null);
        Long oldest = insertDrop(sellerId, categoryId, "ENDED", "TIME_EXPIRED", now.minusDays(3));
        Long middle = insertDrop(sellerId, categoryId, "ENDED", "TIME_EXPIRED", now.minusDays(2));
        Long latest = insertDrop(sellerId, categoryId, "ENDED", "TIME_EXPIRED", now.minusDays(1));
        insertWishUser(oldest, false);
        insertWishUser(oldest, false);
        insertWishUser(oldest, false);
        insertWishUser(middle, false);
        insertWishUser(latest, false);

        // when
        ConversionSample sample = demandAnalysisRepository.findOwnConversionSample(sellerId, categoryId, target, 2);

        // then
        assertThat(sample).isEqualTo(new ConversionSample(2, 2, 0, 0));
    }

    @Test
    @DisplayName("표본 DROP이 없으면 0으로 채운 합계를 반환한다")
    void findOwnConversionSample_returnsZerosWithoutSample() {
        // given
        Long target = insertDrop(sellerId, categoryId, "WISH", null, null);

        // when & then
        assertThat(demandAnalysisRepository.findOwnConversionSample(sellerId, categoryId, target, 20))
                .isEqualTo(ConversionSample.empty());
    }

    @Test
    @DisplayName("시장 표본은 다른 판매자의 기준 시점 이전 종료 DROP만 쓰고 서로 다른 판매자 수를 함께 센다")
    void findMarketConversionSample_filtersOtherSellersBeforeAsOf() {
        // given: 기준 시점 = 오늘 0시(KST)라고 가정
        OffsetDateTime asOf = now;
        Long sellerA = insertSeller();
        Long sellerB = insertSeller();

        // 판매자 A: DROP 2개(WISH 2명 중 1명이 2개 구매, WISH 1명 미구매)
        Long a1 = insertDrop(sellerA, categoryId, "ENDED", "TIME_EXPIRED", asOf.minusDays(1));
        insertOrder(a1, insertWishUser(a1, false), true, false, 2);
        insertWishUser(a1, false);
        Long a2 = insertDrop(sellerA, categoryId, "ENDED", "TIME_EXPIRED", asOf.minusDays(2));
        insertWishUser(a2, false);
        // 판매자 B: DROP 1개(WISH 1명이 1개 구매), 기준 시점 1시간 전 종료
        Long b1 = insertDrop(sellerB, categoryId, "ENDED", "TIME_EXPIRED", asOf.minusHours(1));
        insertOrder(b1, insertWishUser(b1, false), true, false, 1);

        // 빠지는 DROP: 본인 DROP, 기준 시점에 정확히 종료, 기준 시점 이후 종료, 다른 카테고리
        Long own = insertDrop(sellerId, categoryId, "ENDED", "TIME_EXPIRED", asOf.minusDays(1));
        insertOrder(own, insertWishUser(own, false), true, false, 5);
        insertWishUser(insertDrop(sellerB, categoryId, "ENDED", "TIME_EXPIRED", asOf), false);
        insertWishUser(insertDrop(sellerB, categoryId, "ENDED", "TIME_EXPIRED", asOf.plusHours(1)), false);
        insertWishUser(insertDrop(sellerB, otherCategoryId, "ENDED", "TIME_EXPIRED", asOf.minusDays(1)), false);

        // when
        MarketSample sample = demandAnalysisRepository.findMarketConversionSample(sellerId, categoryId, asOf, 20);

        // then: DROP 3개·판매자 2명, WISH 4명 중 2명이 3개 구매
        assertThat(sample).isEqualTo(new MarketSample(3, 2, 4, 2, 3));
    }

    @Test
    @DisplayName("시장 표본의 판매자 수는 최대 개수로 고른 최근 표본 안에서 센다")
    void findMarketConversionSample_countsSellersWithinLimit() {
        // given
        Long oldest = insertDrop(insertSeller(), categoryId, "ENDED", "TIME_EXPIRED", now.minusDays(3));
        Long middle = insertDrop(insertSeller(), categoryId, "ENDED", "TIME_EXPIRED", now.minusDays(2));
        Long latest = insertDrop(insertSeller(), categoryId, "ENDED", "TIME_EXPIRED", now.minusDays(1));
        insertWishUser(oldest, false);
        insertWishUser(middle, false);
        insertWishUser(latest, false);

        // when
        MarketSample sample = demandAnalysisRepository.findMarketConversionSample(sellerId, categoryId, now, 2);

        // then
        assertThat(sample).isEqualTo(new MarketSample(2, 2, 2, 0, 0));
    }

    @Test
    @DisplayName("공개 시점 재고 추천 기록에서 기준·추천 수량·준비 수량과 저장된 응답의 범위를 읽고, 기록이 없으면 비어 있다")
    void findInitialRecommendation_readsSnapshot() {
        // given
        Long dropId = insertDrop(sellerId, categoryId, "WISH", null, null);
        Long withoutRecord = insertDrop(sellerId, categoryId, "WISH", null, null);
        jdbcTemplate.update(
                """
                INSERT INTO drop_stock_recommendations (drop_id, calculation_version, basis, result, recommended_total,
                                                        prepared_total, prepared_quantities, applied, created_at)
                VALUES (?, 's1', 'MARKET', CAST(? AS JSONB), 44, 120, '[]', FALSE, ?)
                """,
                dropId, "{\"recommendation\": {\"low\": 25, \"expected\": 40, \"high\": 60, \"recommended\": 44}}",
                now.minusDays(3));

        // when
        InitialRecommendation initial = demandAnalysisRepository.findInitialRecommendation(dropId).orElseThrow();

        // then
        assertThat(initial).usingRecursiveComparison().ignoringFields("recordedAt")
                .isEqualTo(new InitialRecommendation(null, ConversionBasis.MARKET, 25, 40, 60, 44, 120));
        assertThat(initial.recordedAt()).isEqualTo(now.minusDays(3));
        assertThat(demandAnalysisRepository.findInitialRecommendation(withoutRecord)).isEmpty();
    }

    @Test
    @DisplayName("판매 시작 시점 기록은 DROP당 한 건만 남고, 기준이 없는데 수량이 있으면 제약으로 거부된다")
    void saveSnapshot_keepsFirstRecordPerDrop() {
        // given
        Long dropId = insertDrop(sellerId, categoryId, "GRAB", null, null);

        // when
        demandAnalysisRepository.saveSnapshot(dropId, "v1", "OWN", "{}", 100, 40, 44, 60, now);
        demandAnalysisRepository.saveSnapshot(dropId, "v1", "MARKET", "{}", 999, 1, 1, 1, now.plusHours(1));

        // then
        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT conversion_basis, active_wish_count, expected_total, recommended_total, prepared_total
                FROM demand_analysis_snapshots WHERE drop_id = ?
                """, dropId))
                .containsExactly(entry("conversion_basis", "OWN"), entry("active_wish_count", 100),
                        entry("expected_total", 40), entry("recommended_total", 44), entry("prepared_total", 60));
        Long noBasis = insertDrop(sellerId, categoryId, "GRAB", null, null);
        assertThatThrownBy(() -> demandAnalysisRepository.saveSnapshot(noBasis, "v1", null, "{}", 0, 10, 11, 60, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long insertUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id",
                Long.class,
                "user-" + suffix + "@example.com",
                "회원-" + suffix);
    }

    private Long insertSeller() {
        String suffix = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?) RETURNING id",
                Long.class,
                insertUser(),
                "contact-" + suffix + "@example.com");
    }

    private Long insertCategory() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO categories (code, name) VALUES (?, '테스트') RETURNING id",
                Long.class,
                "TEST-" + UUID.randomUUID());
    }

    private Long insertDrop(Long dropSellerId, Long dropCategoryId, String status, String closeReason, OffsetDateTime closedAt) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee,
                                   shipping_notice, sale_starts_at, sale_ends_at, published_at, grab_started_at,
                                   closed_at, close_reason, cancel_reason)
                VALUES (?, ?, ?, '상품', '설명', 0, '안내', ?, ?, CURRENT_TIMESTAMP,
                        CASE WHEN ? IN ('GRAB', 'ENDED') THEN ? ELSE NULL END,
                        ?, ?, CASE WHEN ? = 'SELLER_CANCELED' THEN '테스트 취소' ELSE NULL END)
                RETURNING id
                """,
                Long.class,
                dropSellerId, dropCategoryId, status, now.plusDays(3), now.plusDays(5),
                status, now.plusDays(3), closedAt, closeReason, closeReason);
    }

    private Long insertGroup(Long dropId, String name, int sortOrder) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO drop_option_groups (drop_id, name, sort_order) VALUES (?, ?, ?) RETURNING id",
                Long.class, dropId, name, sortOrder);
    }

    private Long insertValue(Long groupId, String value) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO drop_option_values (group_id, value, sort_order) VALUES (?, ?, 0) RETURNING id",
                Long.class, groupId, value);
    }

    private Long insertOption(Long dropId, int total, int sortOrder, boolean active) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity,
                                          sold_quantity, withheld_quantity, is_active, sort_order)
                VALUES (?, 1000, ?, 0, 0, 0, ?, ?)
                RETURNING id
                """,
                Long.class, dropId, total, active, sortOrder);
    }

    private void mapValue(Long optionId, Long groupId, Long valueId, Long dropId) {
        jdbcTemplate.update(
                "INSERT INTO drop_option_value_maps (option_id, group_id, value_id, drop_id) VALUES (?, ?, ?, ?)",
                optionId, groupId, valueId, dropId);
    }

    private void insertWish(Long dropId, OffsetDateTime activatedAt, boolean canceled) {
        jdbcTemplate.update(
                """
                INSERT INTO wishes (user_id, drop_id, activated_at, canceled_at)
                VALUES (?, ?, ?, CASE WHEN ? THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END)
                """,
                insertUser(), dropId, activatedAt, canceled, activatedAt);
    }

    // wishes는 UQ(user_id, drop_id)라 WISH 한 건마다 회원을 새로 만든다
    private Long insertWishUser(Long dropId, boolean canceled) {
        Long userId = insertUser();
        jdbcTemplate.update(
                """
                INSERT INTO wishes (user_id, drop_id, activated_at, canceled_at)
                VALUES (?, ?, CURRENT_TIMESTAMP, CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                """,
                userId, dropId, canceled);
        return userId;
    }

    private void insertOrder(Long dropId, Long buyerId, boolean paid, boolean canceled, int quantity) {
        String suffix = UUID.randomUUID().toString();
        String status = canceled ? "CANCELED" : paid ? "PAID" : "PAYMENT_PENDING";
        // payment_expires_at > created_at 제약이 있어 생성 시각도 고정 시각으로 넣는다
        Long orderId = jdbcTemplate.queryForObject(
                """
                INSERT INTO orders (order_number, buyer_id, drop_id, idempotency_key, request_hash, status,
                                    product_name_snapshot, seller_name_snapshot, items_amount, shipping_amount,
                                    total_amount, recipient_name, recipient_phone, postal_code, address_line1,
                                    payment_expires_at, paid_at, canceled_at, cancel_reason, created_at)
                VALUES (?, ?, ?, ?, 'hash', ?, '상품', '브랜드', 1000, 0, 1000,
                        '수령인', '01000000000', '00000', '주소', ?,
                        CASE WHEN ? THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END,
                        CASE WHEN ? THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END,
                        CASE WHEN ? THEN '테스트 취소' ELSE NULL END,
                        ?)
                RETURNING id
                """,
                Long.class,
                "ORDER-" + suffix, buyerId, dropId, suffix, status,
                now.plusHours(1), paid, now, canceled, now, canceled, now);
        Long optionId = insertOption(dropId, 100, 0, true);
        jdbcTemplate.update(
                """
                INSERT INTO order_items (order_id, drop_id, option_id, option_name_snapshot, unit_price, quantity)
                VALUES (?, ?, ?, '옵션', 1000, ?)
                """,
                orderId, dropId, optionId, quantity);
    }
}
