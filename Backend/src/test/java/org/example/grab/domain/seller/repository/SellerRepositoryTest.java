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
        Seller seller = Seller.apply(userId, "브랜드", "contact@example.com", "1234567890", null,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"));

        // when
        sellerRepository.save(seller);
        entityManager.flush();
        entityManager.clear();

        // then
        Seller found = entityManager.find(Seller.class, seller.getId());
        assertThat(found.getUuid()).isEqualTo(seller.getUuid());
        assertThat(found.getStatus()).isEqualTo(SellerStatus.PENDING);
        assertThat(found.getBusinessRegistrationNumber()).isEqualTo("1234567890");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(findApprovedId(publicIdOf(userId))).isEmpty();
    }

    @Test
    @DisplayName("회원당 신청은 하나뿐이라 findByUserId로 상태와 무관하게 조회된다")
    void findsByUserIdRegardlessOfStatus() {
        // given
        Long userId = insertUser();
        insertPendingSeller(userId);

        // when, then
        assertThat(sellerRepository.findByUserId(userId)).isPresent();
        assertThat(sellerRepository.findByUserId(999_999L)).isEmpty();
    }

    @Test
    @DisplayName("사업자등록번호로 기존 신청을 찾을 수 있다")
    void findsByBusinessRegistrationNumber() {
        // given
        Long userId = insertUser();
        insertPendingSeller(userId);

        // when, then
        assertThat(sellerRepository.findByBusinessRegistrationNumber(businessRegistrationNumberOf(userId)))
                .isPresent();
        assertThat(sellerRepository.findByBusinessRegistrationNumber("9999999999")).isEmpty();
    }

    @Test
    @DisplayName("상태로 신청 목록을 페이지 조회할 수 있다")
    void findsByStatusPaged() {
        // given
        Long reviewerId = insertUser();
        Long pendingUserId = insertUser();
        insertPendingSeller(pendingUserId);
        Long approvedUserId = insertUser();
        insertApprovedSeller(approvedUserId, reviewerId);

        // when
        var page = sellerRepository.findByStatus(SellerStatus.PENDING,
                org.springframework.data.domain.PageRequest.of(0, 20));

        // then
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getUserId()).isEqualTo(pendingUserId);
    }

    @Test
    @DisplayName("applicationId(uuid)로 신청을 조회할 수 있다")
    void findsByUuid() {
        // given
        Long userId = insertUser();
        insertPendingSeller(userId);
        Seller seller = sellerRepository.findByUserId(userId).orElseThrow();

        // when, then
        assertThat(sellerRepository.findByUuid(seller.getUuid())).isPresent();
        assertThat(sellerRepository.findByUuid(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("승인/반려용 잠금 조회도 applicationId(uuid)로 신청을 찾는다")
    void findsByUuidForUpdate() {
        // given
        Long userId = insertUser();
        insertPendingSeller(userId);
        Seller seller = sellerRepository.findByUserId(userId).orElseThrow();

        // when, then
        assertThat(sellerRepository.findByUuidForUpdate(seller.getUuid())).isPresent();
        assertThat(sellerRepository.findByUuidForUpdate(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("운영자 심사 목록은 상태를 지정하면 해당 상태만, 생략하면 전체를 신청자 public_id와 함께 반환한다")
    void findsApplicationsForReview() {
        // given
        Long reviewerId = insertUser();
        Long pendingUserId = insertUser();
        insertPendingSeller(pendingUserId);
        Long approvedUserId = insertUser();
        insertApprovedSeller(approvedUserId, reviewerId);

        // when
        var pendingOnly = sellerRepository.findApplications(SellerStatus.PENDING,
                org.springframework.data.domain.PageRequest.of(0, 20));
        var all = sellerRepository.findApplications(null, org.springframework.data.domain.PageRequest.of(0, 20));

        // then
        assertThat(pendingOnly.getContent()).hasSize(1);
        assertThat(pendingOnly.getContent().get(0).userId()).isEqualTo(publicIdOf(pendingUserId));
        assertThat(all.getTotalElements()).isEqualTo(2);
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
                "INSERT INTO sellers (user_id, brand_name, contact_email, business_registration_number) "
                        + "VALUES (?, '브랜드', ?, ?)",
                userId, "contact-" + userId + "@example.com", businessRegistrationNumberOf(userId));
    }

    // 사용자별로 겹치지 않는 10자리 더미 사업자등록번호
    private String businessRegistrationNumberOf(Long userId) {
        return String.format("%010d", userId);
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
