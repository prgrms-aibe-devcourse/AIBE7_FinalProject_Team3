package org.example.grab.domain.drop.repository;

import jakarta.persistence.EntityManager;
import org.example.grab.global.config.JpaConfig;
import org.example.grab.domain.drop.entity.Drop;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
}
