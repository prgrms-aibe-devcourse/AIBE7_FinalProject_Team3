package org.example.grab.domain.seller.repository;

import jakarta.persistence.EntityManager;
import org.example.grab.domain.seller.entity.Seller;
import org.example.grab.domain.seller.entity.SellerStatus;
import org.example.grab.global.config.JpaConfig;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// D2: 회원 public_id로 승인된 판매자의 sellers.id를 조회한다(GR-32 M03-05-2)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(JpaConfig.class) // JpaConfig: createdAt, updatedAt auditing을 켬
@Testcontainers
class SellerRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private SellerRepository sellerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("APPROVED 판매자는 회원 public_id로 sellers.id가 조회된다")
    void findsApprovedSellerId() {
        // given
        Long reviewerId = insertUser();
        Long userId = insertUser();
        Long sellerId = insertApprovedSeller(userId, reviewerId);

        // when, then
        assertThat(findApprovedId(publicIdOf(userId))).contains(sellerId);
    }

    @Test
    @DisplayName("PENDING·REJECTED 판매자, 판매자가 아닌 회원, 없는 public_id는 빈 결과다")
    void returnsEmptyUnlessApproved() {
        // given
        Long reviewerId = insertUser();
        Long pendingUserId = insertUser();
        insertPendingSeller(pendingUserId);
        Long rejectedUserId = insertUser();
        insertRejectedSeller(rejectedUserId, reviewerId);
        Long plainUserId = insertUser();

        // when, then
        assertThat(findApprovedId(publicIdOf(pendingUserId))).isEmpty();
        assertThat(findApprovedId(publicIdOf(rejectedUserId))).isEmpty();
        assertThat(findApprovedId(publicIdOf(plainUserId))).isEmpty();
        assertThat(findApprovedId(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("엔티티로 저장한 신청은 매핑이 DB와 맞고, 승인 전이라 조회되지 않는다")
    void persistsSellerEntityAsPending() {
        // given
        Long userId = insertUser();
        Seller seller = Seller.apply(userId, "브랜드", "contact@example.com", null,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"));

        // when
        sellerRepository.save(seller);
        entityManager.flush();
        entityManager.clear();

        // then
        Seller found = entityManager.find(Seller.class, seller.getId());
        assertThat(found.getUuid()).isEqualTo(seller.getUuid());
        assertThat(found.getStatus()).isEqualTo(SellerStatus.PENDING);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(findApprovedId(publicIdOf(userId))).isEmpty();
    }

    private Optional<Long> findApprovedId(UUID userPublicId) {
        return sellerRepository.findIdByUserPublicIdAndStatus(userPublicId, SellerStatus.APPROVED);
    }

    private Long insertUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id",
                Long.class,
                "user-" + suffix + "@example.com",
                "회원-" + suffix);
    }

    private UUID publicIdOf(Long userId) {
        return jdbcTemplate.queryForObject("SELECT public_id FROM users WHERE id = ?", UUID.class, userId);
    }

    private void insertPendingSeller(Long userId) {
        jdbcTemplate.update(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?)",
                userId, "contact-" + userId + "@example.com");
    }

    // ck_sellers_review: 심사가 끝난 상태는 reviewed_by·reviewed_at이 필요하다
    private Long insertApprovedSeller(Long userId, Long reviewerId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
                VALUES (?, '브랜드', ?, 'APPROVED', ?, CURRENT_TIMESTAMP) RETURNING id
                """,
                Long.class,
                userId, "contact-" + userId + "@example.com", reviewerId);
    }

    private void insertRejectedSeller(Long userId, Long reviewerId) {
        jdbcTemplate.update(
                """
                INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at,
                                     rejection_reason)
                VALUES (?, '브랜드', ?, 'REJECTED', ?, CURRENT_TIMESTAMP, '서류 미비')
                """,
                userId, "contact-" + userId + "@example.com", reviewerId);
    }
}
