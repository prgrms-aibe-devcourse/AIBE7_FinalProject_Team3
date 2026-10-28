package org.example.grab.domain.wish.repository;

import org.example.grab.domain.wish.dto.WishListProjection;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * GR-54 내 WISH 목록 쿼리의 저장소 검증. 활성 WISH만, 썸네일·최저가 계산, 정렬·페이지를
 * 실제 PostgreSQL에서 확인한다. GR-54 5절의 "목록 쿼리 수 고정"·"인덱스"는 후속 통합 테스트에서 다룬다.
 */
@SpringBootTest
@Testcontainers
class WishListQueryIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private WishRepository wishRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long otherUserId;
    private Long sellerId;

    @BeforeEach
    void setUp() {
        userId = insertUser();
        otherUserId = insertUser();
        sellerId = insertSeller();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM wishes WHERE user_id IN (?, ?)", userId, otherUserId);
        jdbcTemplate.update(
                "DELETE FROM drop_images WHERE drop_id IN (SELECT id FROM drops WHERE seller_id = ?)", sellerId);
        jdbcTemplate.update(
                "DELETE FROM drop_options WHERE drop_id IN (SELECT id FROM drops WHERE seller_id = ?)", sellerId);
        jdbcTemplate.update("DELETE FROM drops WHERE seller_id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM sellers WHERE id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", userId, otherUserId);
    }

    @Test
    @DisplayName("본인 활성 WISH만 최신 등록순으로 반환하고 취소·타 사용자 WISH는 제외한다")
    void findsOnlyOwnActiveWishes() {
        // given
        Long olderDrop = insertDrop("첫번째", "WISH");
        Long newerDrop = insertDrop("두번째", "GRAB");
        Long canceledDrop = insertDrop("취소됨", "WISH");
        Long otherDrop = insertDrop("타인", "WISH");
        insertWish(userId, olderDrop, true, 2);
        insertWish(userId, newerDrop, true, 1);
        insertWish(userId, canceledDrop, false, 0);
        insertWish(otherUserId, otherDrop, true, 0);

        // when
        Page<WishListProjection> page = wishRepository.findActiveWishes(userId, PageRequest.of(0, 20));

        // then
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(WishListProjection::getDropId)
                .containsExactly(newerDrop, olderDrop);
    }

    @Test
    @DisplayName("DROP 상태가 바뀌어도 현재 status를 그대로 반환한다")
    void exposesCurrentDropStatus() {
        // given
        Long endedDrop = insertDrop("종료", "ENDED");
        insertWish(userId, endedDrop, true, 0);

        // when
        Page<WishListProjection> page = wishRepository.findActiveWishes(userId, PageRequest.of(0, 20));

        // then
        assertThat(page.getContent().get(0).getStatus()).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("썸네일은 GALLERY 이미지 sort_order 최솟값, 최저가는 활성 SKU만 반영한다")
    void calculatesThumbnailAndMinPrice() {
        // given
        Long dropId = insertDrop("상품", "WISH");
        insertImage(dropId, "https://img/2.jpg", 2);
        insertImage(dropId, "https://img/0.jpg", 0);
        insertOption(dropId, 120000L, true);
        insertOption(dropId, 99000L, true);
        insertOption(dropId, 50000L, false); // 비활성은 최저가에서 제외
        insertWish(userId, dropId, true, 0);

        // when
        WishListProjection projection =
                wishRepository.findActiveWishes(userId, PageRequest.of(0, 20)).getContent().get(0);

        // then
        assertThat(projection.getThumbnailUrl()).isEqualTo("https://img/0.jpg");
        assertThat(projection.getMinPrice()).isEqualTo(99000L);
    }

    @Test
    @DisplayName("썸네일은 GALLERY만 쓰고 DETAIL만 있으면 null이다")
    void thumbnailIgnoresDetailImages() {
        // given
        Long mixed = insertDrop("혼합", "WISH");
        Long detailOnly = insertDrop("상세만", "WISH");
        insertTypedImage(mixed, "https://img/detail.jpg", "DETAIL", 0);
        insertImage(mixed, "https://img/gallery.jpg", 0);
        insertTypedImage(detailOnly, "https://img/detail-only.jpg", "DETAIL", 0);
        insertWish(userId, mixed, true, 1);
        insertWish(userId, detailOnly, true, 0);

        // when
        List<WishListProjection> rows = wishRepository.findActiveWishes(userId, PageRequest.of(0, 20)).getContent();

        // then: 최신 등록순이므로 detailOnly, mixed 순서다
        assertThat(rows).extracting(WishListProjection::getThumbnailUrl)
                .containsExactly(null, "https://img/gallery.jpg");
    }

    @Test
    @DisplayName("이미지가 없거나 활성 SKU가 없으면 썸네일·최저가는 null이다")
    void returnsNullWhenNoImageOrActiveOption() {
        // given
        Long dropId = insertDrop("상품", "WISH");
        insertOption(dropId, 10000L, false); // 활성 SKU 없음
        insertWish(userId, dropId, true, 0);

        // when
        WishListProjection projection =
                wishRepository.findActiveWishes(userId, PageRequest.of(0, 20)).getContent().get(0);

        // then
        assertThat(projection.getThumbnailUrl()).isNull();
        assertThat(projection.getMinPrice()).isNull();
    }

    @Test
    @DisplayName("같은 activated_at에서도 id DESC로 페이지 순서가 안정적이다")
    void stableOrderOnEqualActivatedAt() {
        // given
        Long first = insertDrop("A", "WISH");
        Long second = insertDrop("B", "WISH");
        Long third = insertDrop("C", "WISH");
        insertWish(userId, first, true, 0);
        insertWish(userId, second, true, 0);
        insertWish(userId, third, true, 0);

        // when
        Page<WishListProjection> page =
                wishRepository.findActiveWishes(userId, PageRequest.of(0, 2));

        // then
        assertThat(page.getContent()).extracting(WishListProjection::getDropId)
                .containsExactly(third, second);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    @DisplayName("활성 WISH는 DROP 상태와 무관하게 각 DROP의 판매 시작 예정 시각을 반환한다")
    void returnsSaleStartsAtPerDropStatus() {
        // given
        OffsetDateTime wishStart = OffsetDateTime.parse("2026-09-20T05:00:00Z");
        OffsetDateTime grabStart = OffsetDateTime.parse("2026-09-21T05:00:00Z");
        OffsetDateTime endedStart = OffsetDateTime.parse("2026-09-22T05:00:00Z");
        OffsetDateTime canceledStart = OffsetDateTime.parse("2026-09-23T05:00:00Z");
        Long wishDrop = insertDrop("위시", "WISH", wishStart);
        Long grabDrop = insertDrop("그랩", "GRAB", grabStart);
        Long endedDrop = insertDrop("종료", "ENDED", endedStart);
        Long canceledDrop = insertDrop("취소", "CANCELED", canceledStart);
        insertWish(userId, wishDrop, true, 3);
        insertWish(userId, grabDrop, true, 2);
        insertWish(userId, endedDrop, true, 1);
        insertWish(userId, canceledDrop, true, 0);

        // when
        Page<WishListProjection> page = wishRepository.findActiveWishes(userId, PageRequest.of(0, 20));

        // then: 최신 등록순이므로 canceled, ended, grab, wish 순서다
        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent())
                .extracting(WishListProjection::getDropId, WishListProjection::getSaleStartsAt)
                .containsExactly(
                        tuple(canceledDrop, canceledStart.toInstant()),
                        tuple(endedDrop, endedStart.toInstant()),
                        tuple(grabDrop, grabStart.toInstant()),
                        tuple(wishDrop, wishStart.toInstant()));
    }

    @Test
    @DisplayName("작은 페이지로 나눠 조회해도 각 항목의 saleStartsAt이 뒤섞이지 않는다")
    void returnsSaleStartsAtConsistentlyAcrossPages() {
        // given
        OffsetDateTime base = OffsetDateTime.parse("2026-09-20T05:00:00Z");
        int total = 5;
        List<Long> dropIds = new ArrayList<>();
        List<OffsetDateTime> starts = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            OffsetDateTime start = base.plusDays(i);
            Long dropId = insertDrop("상품" + i, "WISH", start);
            insertWish(userId, dropId, true, i); // i=0이 가장 최근 등록이다
            dropIds.add(dropId);
            starts.add(start);
        }
        // 제외 대상: 취소한 WISH와 타 사용자 WISH
        Long canceledDrop = insertDrop("취소", "WISH", base.plusDays(10));
        insertWish(userId, canceledDrop, false, 0);
        Long otherDrop = insertDrop("타인", "WISH", base.plusDays(11));
        insertWish(otherUserId, otherDrop, true, 0);

        // when
        Page<WishListProjection> firstPage = wishRepository.findActiveWishes(userId, PageRequest.of(0, 2));
        List<WishListProjection> items = new ArrayList<>(firstPage.getContent());
        items.addAll(wishRepository.findActiveWishes(userId, PageRequest.of(1, 2)).getContent());
        items.addAll(wishRepository.findActiveWishes(userId, PageRequest.of(2, 2)).getContent());

        // then
        assertThat(firstPage.getTotalElements()).isEqualTo(total);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(items).hasSize(total);
        for (int i = 0; i < total; i++) {
            assertThat(items.get(i).getDropId()).isEqualTo(dropIds.get(i));
            assertThat(items.get(i).getSaleStartsAt()).isEqualTo(starts.get(i).toInstant());
        }
    }

    private Long insertUser() {
        String unique = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname)
                VALUES (?, 'encoded-password', ?)
                RETURNING id
                """,
                Long.class,
                unique + "@example.com",
                "회원-" + unique);
    }

    private Long insertSeller() {
        String unique = UUID.randomUUID().toString();
        Long ownerUserId = insertUser();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email)
                VALUES (?, 'GRAB 판매자', ?)
                RETURNING id
                """,
                Long.class,
                ownerUserId,
                "seller-" + unique + "@example.com");
    }

    private Long insertDrop(String name, String status) {
        return insertDrop(name, status, OffsetDateTime.now().minusHours(2));
    }

    // GRAB은 grab_started_at, ENDED·CANCELED는 closed_at·close_reason까지 각 CHECK 제약을 만족해야 한다.
    private Long insertDrop(String name, String status, OffsetDateTime saleStartsAt) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                                   sale_starts_at, sale_ends_at, published_at, grab_started_at, closed_at, close_reason)
                VALUES (?, 1, ?, ?, '설명', 3000, '배송 안내',
                        ?, ?, CURRENT_TIMESTAMP - INTERVAL '1 day',
                        CASE WHEN ? IN ('GRAB', 'ENDED') THEN CURRENT_TIMESTAMP - INTERVAL '1 hour' ELSE NULL END,
                        CASE WHEN ? IN ('ENDED', 'CANCELED') THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CASE WHEN ? = 'ENDED' THEN 'TIME_EXPIRED'
                             WHEN ? = 'CANCELED' THEN 'SELLER_CANCELED' ELSE NULL END)
                RETURNING id
                """,
                Long.class,
                sellerId,
                status,
                name,
                saleStartsAt,
                saleStartsAt.plusHours(4),
                status,
                status,
                status,
                status);
    }

    private void insertWish(Long wishUserId, Long dropId, boolean active, int activatedMinutesAgo) {
        jdbcTemplate.update(
                """
                INSERT INTO wishes (user_id, drop_id, activated_at, canceled_at)
                VALUES (?, ?, CURRENT_TIMESTAMP - (? * INTERVAL '1 minute'),
                        CASE WHEN ? THEN NULL ELSE CURRENT_TIMESTAMP END)
                """,
                wishUserId, dropId, activatedMinutesAgo, active);
    }

    private void insertTypedImage(Long dropId, String url, String imageType, int sortOrder) {
        jdbcTemplate.update(
                """
                INSERT INTO drop_images (drop_id, image_url, image_type, sort_order, alt_text)
                VALUES (?, ?, ?, ?, '상품')
                """,
                dropId, url, imageType, sortOrder);
    }

    private void insertImage(Long dropId, String url, int sortOrder) {
        jdbcTemplate.update(
                """
                INSERT INTO drop_images (drop_id, image_url, sort_order, alt_text)
                VALUES (?, ?, ?, '상품')
                """,
                dropId, url, sortOrder);
    }

    private void insertOption(Long dropId, Long unitPrice, boolean active) {
        jdbcTemplate.update(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, is_active, sort_order)
                VALUES (?, ?, 10, ?, 0)
                """,
                dropId, unitPrice, active);
    }
}
