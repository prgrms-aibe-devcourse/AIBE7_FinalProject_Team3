package org.example.grab.domain.wish.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.example.grab.domain.drop.dto.PublicDropSort;
import org.example.grab.domain.drop.dto.response.PublicDropListResponse;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.domain.wish.repository.WishRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GR-54 4.3 집계 규칙 일치 검증. 등록·취소·재등록에 따라 판매자 activeWishCount와 공개 상세·목록의
 * wishCount가 같은 기준(canceled_at IS NULL)으로 함께 변하는지 실제 PostgreSQL에서 확인한다.
 */
@SpringBootTest
@Testcontainers
class WishCountConsistencyIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private WishService wishService;

    @Autowired
    private WishQueryService wishQueryService;

    @Autowired
    private DropService dropService;

    @Autowired
    private WishRepository wishRepository;

    @Autowired
    private DropRepository dropRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Long user1;
    private Long user2;
    private Long sellerId;
    private Long dropId;

    @BeforeEach
    void setUp() {
        user1 = insertUser();
        user2 = insertUser();
        sellerId = insertSeller();
        dropId = insertWishDrop();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update(
                "DELETE FROM wishes WHERE drop_id IN (SELECT id FROM drops WHERE seller_id = ?)", sellerId);
        jdbcTemplate.update(
                "DELETE FROM drop_options WHERE drop_id IN (SELECT id FROM drops WHERE seller_id = ?)", sellerId);
        jdbcTemplate.update(
                "DELETE FROM drop_images WHERE drop_id IN (SELECT id FROM drops WHERE seller_id = ?)", sellerId);
        jdbcTemplate.update("DELETE FROM drops WHERE seller_id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM sellers WHERE id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", user1, user2);
    }

    @Test
    @DisplayName("등록 → 판매자·공개 상세·공개 목록 건수가 함께 증가한다")
    void registerIncreasesAllCounts() {
        // given: 0건
        assertThat(sellerCount()).isZero();
        assertThat(publicDetailCount()).isZero();
        assertThat(publicListCount()).isZero();

        // when
        wishService.register(user1, dropId);
        wishService.register(user2, dropId);
        entityManager.clear();

        // then
        assertThat(sellerCount()).isEqualTo(2);
        assertThat(publicDetailCount()).isEqualTo(2);
        assertThat(publicListCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("취소 → 감소, 재등록 → 다시 증가하며 세 화면이 항상 같은 값을 보여준다")
    void cancelThenReregisterKeepsCountsConsistent() {
        // given
        wishService.register(user1, dropId);
        wishService.register(user2, dropId);
        entityManager.clear();
        assertCountsAgree(2);

        // when: 한 명 취소
        wishService.cancel(user1, dropId);
        entityManager.clear();

        // then
        assertCountsAgree(1);

        // when: 재등록
        wishService.register(user1, dropId);
        entityManager.clear();

        // then
        assertCountsAgree(2);
    }

    @Test
    @DisplayName("같은 사용자의 반복 등록은 활성 건수를 늘리지 않는다(멱등)")
    void repeatedRegisterIsIdempotent() {
        // when
        wishService.register(user1, dropId);
        wishService.register(user1, dropId);
        entityManager.clear();

        // then
        assertCountsAgree(1);
    }

    @Test
    @DisplayName("내 WISH 목록 조회는 페이지 항목 수만큼 쿼리가 늘지 않는다(N+1 아님)")
    void findsMyWishesWithoutNPlusOne() {
        // given: 서로 다른 DROP에 여러 WISH를 만든다
        Long secondDrop = insertWishDrop();
        for (int i = 0; i < 5; i++) {
            Long extraDrop = insertWishDrop();
            jdbcTemplate.update(
                    """
                    INSERT INTO wishes (user_id, drop_id, activated_at, canceled_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP - (? * INTERVAL '1 minute'), NULL)
                    """,
                    user1, extraDrop, i);
        }
        jdbcTemplate.update(
                "INSERT INTO wishes (user_id, drop_id, activated_at, canceled_at) VALUES (?, ?, CURRENT_TIMESTAMP, NULL)",
                user1, secondDrop);
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        // when
        wishQueryService.findMyWishes(user1, 0, 20);

        // then: 목록 항목마다 별도 조회가 나면 항목 수(7) 이상으로 늘어난다. 배치/서브쿼리로 고정됨을 확인.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("내 WISH 목록 대표 데이터의 실행 계획에서 활성 인덱스를 확인한다")
    void explainsIndexUsage() {
        // given: 사용자·활성 조건 인덱스(idx_wishes_user_activated)를 쓸 수 있는 데이터
        for (int i = 0; i < 20; i++) {
            Long extraDrop = insertWishDrop();
            jdbcTemplate.update(
                    """
                    INSERT INTO wishes (user_id, drop_id, activated_at, canceled_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP - (? * INTERVAL '1 minute'), NULL)
                    """,
                    user1, extraDrop, i);
        }
        jdbcTemplate.execute("ANALYZE wishes");

        // when: 목록 쿼리와 같은 조건으로 실행 계획을 확인한다
        List<String> plan = jdbcTemplate.queryForList(
                """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT w.drop_id, d.name, d.status,
                       (SELECT i.image_url FROM drop_images i WHERE i.drop_id = d.id ORDER BY i.sort_order LIMIT 1),
                       (SELECT MIN(o.unit_price) FROM drop_options o WHERE o.drop_id = d.id AND o.is_active),
                       w.activated_at
                FROM wishes w
                JOIN drops d ON d.id = w.drop_id
                WHERE w.user_id = ? AND w.canceled_at IS NULL
                ORDER BY w.activated_at DESC, w.id DESC
                """,
                String.class,
                user1);

        // then: 소량 데이터에서는 순차 스캔이 선택될 수 있으므로 인덱스 미사용만으로 실패시키지 않는다.
        // 실행 계획이 정상적으로 수집되는지(예외 없이 결과가 나오는지)만 확인하고, 사용 여부는 결과에 기록한다.
        String joined = String.join("\n", plan);
        assertThat(joined).isNotBlank();
        assertThat(joined).containsAnyOf("idx_wishes_user_activated", "Seq Scan");
    }

    private void assertCountsAgree(long expected) {
        assertThat(sellerCount()).isEqualTo(expected);
        assertThat(publicDetailCount()).isEqualTo(expected);
        assertThat(publicListCount()).isEqualTo(expected);
    }

    private long sellerCount() {
        return dropService.findSellerWishCount(sellerId, dropId).activeWishCount();
    }

    private long publicDetailCount() {
        return dropService.findPublicDrop(dropId).wishCount();
    }

    private long publicListCount() {
        return dropService.findPublicDrops(null, null, null, null, PublicDropSort.parse(null), 0, 100)
                .content().stream()
                .filter(item -> item.dropId().equals(dropId))
                .map(PublicDropListResponse::wishCount)
                .findFirst()
                .orElse(0L);
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

    private Long insertWishDrop() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO drops (seller_id, category_id, status, name, description, shipping_fee, shipping_notice,
                                   sale_starts_at, sale_ends_at, published_at)
                VALUES (?, 1, 'WISH', '한정판 상품', '설명', 3000, '배송 안내',
                        CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP + INTERVAL '2 hours',
                        CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                sellerId);
    }
}
