package org.example.grab.domain.demand.repository;

import org.example.grab.domain.demand.entity.DemandPlan;
import org.example.grab.domain.demand.entity.DemandPlanStatus;
import org.example.grab.domain.demand.entity.ExplanationStatus;
import org.example.grab.global.config.JpaConfig;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(JpaConfig.class)
@Testcontainers
class DemandPlanRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DemandPlanRepository demandPlanRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long dropId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id",
                Long.class, "user-" + UUID.randomUUID() + "@example.com", "회원-" + UUID.randomUUID());
        Long sellerId = jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?) RETURNING id",
                Long.class, userId, "contact-" + UUID.randomUUID() + "@example.com");
        dropId = jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id, status) VALUES (?, 'DRAFT') RETURNING id", Long.class, sellerId);
    }

    @Test
    @DisplayName("계획을 저장하면 JSON 스냅샷·공개 식별자·생성 시각이 함께 남고 공개 식별자로 다시 찾는다")
    void save_roundTripsJsonSnapshot() {
        // given
        DemandPlan plan = DemandPlan.draft(dropId, userId, "{\"safetyStockRate\": {\"value\": 0.1}}", "OWN",
                "{\"plan\": {\"recommended\": 44}}", "v1", ExplanationStatus.DISABLED);

        // when
        demandPlanRepository.saveAndFlush(plan);
        DemandPlan found = demandPlanRepository.findByUuid(plan.getUuid()).orElseThrow();

        // then
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getResult()).contains("\"recommended\": 44");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT result -> 'plan' ->> 'recommended' FROM demand_plans WHERE public_id = ?",
                String.class, plan.getUuid())).isEqualTo("44");
        assertThat(found.getExplanationStatus()).isEqualTo(ExplanationStatus.DISABLED);
    }

    @Test
    @DisplayName("결정을 기록하면 잠금 조회로 다시 읽은 행에 결정 상태·수량이 저장된다")
    void decide_persistsDecision() {
        // given
        DemandPlan plan = demandPlanRepository.saveAndFlush(draft());
        OffsetDateTime decidedAt = OffsetDateTime.parse("2026-10-22T05:00:00Z");

        // when
        DemandPlan locked = demandPlanRepository.findByUuidForUpdate(plan.getUuid()).orElseThrow();
        locked.decide(DemandPlanStatus.MODIFIED, "[{\"optionId\": 1, \"quantity\": 5}]", "조정", decidedAt);
        demandPlanRepository.flush();

        // then
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status || ':' || (decided_quantities -> 0 ->> 'quantity') FROM demand_plans WHERE id = ?",
                String.class, plan.getId())).isEqualTo("MODIFIED:5");
    }

    @Test
    @DisplayName("DROP별 계획 목록은 최근 저장순이다")
    void findByDropIdLatestFirst_ordersByCreatedAtDesc() {
        // given
        DemandPlan older = demandPlanRepository.saveAndFlush(draft());
        DemandPlan newer = demandPlanRepository.saveAndFlush(draft());
        jdbcTemplate.update("UPDATE demand_plans SET created_at = created_at - INTERVAL '1 hour' WHERE id = ?",
                older.getId());

        // when
        Page<DemandPlan> page = demandPlanRepository.findByDropIdLatestFirst(dropId, PageRequest.of(0, 20));

        // then
        assertThat(page.getContent()).extracting(DemandPlan::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    @DisplayName("수량 없는 MODIFIED처럼 결정 상태와 결정 필드가 어긋난 행은 DB 제약이 거부한다")
    void checkConstraint_rejectsInconsistentDecision() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO demand_plans (drop_id, created_by, assumptions, result, calculation_version, status,
                                          decided_at, explanation_status)
                VALUES (?, ?, '{}', '{}', 'v1', 'MODIFIED', CURRENT_TIMESTAMP, 'DISABLED')
                """, dropId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 제약 위반은 테스트 트랜잭션을 중단시키므로 검증마다 테스트를 나눈다
    @Test
    @DisplayName("GENERATED가 아닌 계획에 설명이 있으면 DB 제약이 거부한다")
    void checkConstraint_rejectsExplanationWithoutGenerated() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO demand_plans (drop_id, created_by, assumptions, result, calculation_version,
                                          explanation_status, explanation)
                VALUES (?, ?, '{}', '{}', 'v1', 'FAILED', '{}')
                """, dropId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private DemandPlan draft() {
        return DemandPlan.draft(dropId, userId, "{}", null, "{}", "v1", ExplanationStatus.DISABLED);
    }
}
