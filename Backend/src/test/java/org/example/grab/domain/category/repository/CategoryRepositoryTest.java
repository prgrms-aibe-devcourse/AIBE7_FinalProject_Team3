package org.example.grab.domain.category.repository;

import org.example.grab.domain.category.entity.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class CategoryRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway 시드로 활성 카테고리 8건이 등록된다")
    void seedsActiveCategories() {
        // when
        List<Category> categories = categoryRepository.findAll();

        // then
        assertThat(categories).hasSize(8);
        assertThat(categories).extracting(Category::getCode)
                .containsExactlyInAnyOrder(
                        "FASHION", "SHOES", "BAG_ACC", "BEAUTY", "DIGITAL", "LIVING", "FOOD", "HOBBY");
        assertThat(categories).allMatch(Category::isActive);
    }

    @Test
    @DisplayName("비활성 카테고리는 제외하고 id 오름차순으로 반환한다")
    void findsOnlyActiveCategoriesInIdOrder() {
        // given
        jdbcTemplate.update(
                "INSERT INTO categories (code, name, is_active) VALUES (?, '비활성', FALSE)",
                "INACTIVE-" + UUID.randomUUID());

        // when
        List<Category> categories = categoryRepository.findAllByActiveTrueOrderByIdAsc();

        // then
        assertThat(categories).hasSize(8);
        assertThat(categories).extracting(Category::getCode)
                .containsExactly("FASHION", "SHOES", "BAG_ACC", "BEAUTY", "DIGITAL", "LIVING", "FOOD", "HOBBY");
        assertThat(categories).extracting(Category::getId).isSorted();
    }
}
