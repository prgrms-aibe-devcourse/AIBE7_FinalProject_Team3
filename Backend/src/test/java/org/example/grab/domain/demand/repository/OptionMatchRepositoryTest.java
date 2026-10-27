package org.example.grab.domain.demand.repository;

import org.example.grab.domain.demand.repository.OptionMatchRepository.OptionSales;
import org.example.grab.support.TestcontainersConfiguration;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(OptionMatchRepository.class)
@Testcontainers
class OptionMatchRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private OptionMatchRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("표본 DROP의 (그룹명, 값)마다 그 값이 들어간 SKU 판매량을 합친다")
    void findOptionSales_sumsSoldPerValue() {
        // given: 색상(블랙·화이트) × 사이즈(M·L) 4개 SKU
        long sellerId = insertSeller();
        long dropId = jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id, status) VALUES (?, 'DRAFT') RETURNING id", Long.class, sellerId);
        long color = insertGroup(dropId, "색상", 0);
        long size = insertGroup(dropId, "사이즈", 1);
        long black = insertValue(color, "블랙");
        long white = insertValue(color, "화이트");
        long m = insertValue(size, "M");
        long l = insertValue(size, "L");
        insertSku(dropId, 5, color, black, size, m);
        insertSku(dropId, 3, color, black, size, l);
        insertSku(dropId, 2, color, white, size, m);
        insertSku(dropId, 0, color, white, size, l);

        // when
        List<OptionSales> sales = repository.findOptionSales(List.of(dropId));

        // then
        assertThat(sales).containsExactlyInAnyOrder(
                new OptionSales(dropId, sellerId, "색상", "블랙", 8),
                new OptionSales(dropId, sellerId, "색상", "화이트", 2),
                new OptionSales(dropId, sellerId, "사이즈", "M", 7),
                new OptionSales(dropId, sellerId, "사이즈", "L", 3));
        assertThat(repository.findOptionSales(List.of())).isEmpty();
    }

    @Test
    @DisplayName("매칭을 입력 해시로 저장·조회하고, 같은 입력으로 다시 저장하면 먼저 저장된 값이 남는다")
    void saveMatches_keepsFirst() {
        String hash = UUID.randomUUID().toString().replace("-", "") + "0".repeat(32);
        OffsetDateTime now = OffsetDateTime.parse("2026-10-27T05:00:00Z");

        repository.saveMatches(hash, "{\"matches\":[]}", "gemini-2.5-flash-lite", "m1", now);
        repository.saveMatches(hash, "{\"matches\":[{\"group\":0}]}", "other", "m1", now);

        assertThat(repository.findMatches(hash)).hasValueSatisfying(json -> assertThat(json).doesNotContain("group"));
        assertThat(repository.findMatches("f".repeat(64))).isEmpty();
    }

    private long insertSeller() {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id",
                Long.class, "user-" + suffix + "@example.com", "회원-" + suffix);
        return jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?) RETURNING id",
                Long.class, userId, "contact-" + suffix + "@example.com");
    }

    private long insertGroup(long dropId, String name, int sortOrder) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO drop_option_groups (drop_id, name, sort_order) VALUES (?, ?, ?) RETURNING id",
                Long.class, dropId, name, sortOrder);
    }

    private long insertValue(long groupId, String value) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO drop_option_values (group_id, value, sort_order) VALUES (?, ?, 0) RETURNING id",
                Long.class, groupId, value);
    }

    private void insertSku(long dropId, int sold, long firstGroup, long firstValue, long secondGroup,
                           long secondValue) {
        Long optionId = jdbcTemplate.queryForObject("""
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity, sold_quantity,
                                          withheld_quantity, is_active, sort_order)
                VALUES (?, 20000, 10, 0, ?, 0, TRUE, 0)
                RETURNING id
                """, Long.class, dropId, sold);
        jdbcTemplate.update("INSERT INTO drop_option_value_maps (option_id, group_id, value_id, drop_id) VALUES (?, ?, ?, ?)",
                optionId, firstGroup, firstValue, dropId);
        jdbcTemplate.update("INSERT INTO drop_option_value_maps (option_id, group_id, value_id, drop_id) VALUES (?, ?, ?, ?)",
                optionId, secondGroup, secondValue, dropId);
    }
}
