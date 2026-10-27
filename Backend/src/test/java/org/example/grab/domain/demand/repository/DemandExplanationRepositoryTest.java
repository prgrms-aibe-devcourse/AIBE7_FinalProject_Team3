package org.example.grab.domain.demand.repository;

import org.example.grab.domain.demand.repository.DemandExplanationRepository.StoredExplanation;
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

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(DemandExplanationRepository.class)
@Testcontainers
class DemandExplanationRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final String HASH = "a".repeat(64);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-27T05:00:00Z");

    @Autowired
    private DemandExplanationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("설명을 DROP·입력 해시로 저장·조회하고, 같은 입력으로 다시 저장하면 먼저 저장된 설명이 남는다")
    void save_keepsFirstPerInput() {
        // given
        long dropId = insertDrop();

        // when
        repository.save(dropId, HASH, "GENERATED", "{\"summary\":\"처음 설명\"}", "gemini-2.5-flash", "v1", 812, now);
        repository.save(dropId, HASH, "DISCARDED", null, "gemini-2.5-flash", "v1", 500, now.plusMinutes(1));

        // then
        StoredExplanation stored = repository.find(dropId, HASH).orElseThrow();
        assertThat(stored.status()).isEqualTo("GENERATED");
        assertThat(stored.explanationJson()).contains("처음 설명");
        assertThat(stored.createdAt()).isEqualTo(now);
        assertThat(repository.find(dropId, "b".repeat(64))).isEmpty();
    }

    @Test
    @DisplayName("GENERATED에는 설명이 있어야 하고 DISCARDED에는 없어야 한다")
    void save_rejectsMismatchedExplanation() {
        long dropId = insertDrop();

        assertThatThrownBy(() -> repository.save(dropId, HASH, "GENERATED", null, "m", "v1", 1, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertDrop() {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id",
                Long.class, "user-" + suffix + "@example.com", "회원-" + suffix);
        Long sellerId = jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?) RETURNING id",
                Long.class, userId, "contact-" + suffix + "@example.com");
        return jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id, status) VALUES (?, 'DRAFT') RETURNING id", Long.class, sellerId);
    }
}
