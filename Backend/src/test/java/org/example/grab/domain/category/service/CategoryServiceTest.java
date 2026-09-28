package org.example.grab.domain.category.service;

import org.example.grab.domain.category.entity.Category;
import org.example.grab.domain.category.repository.CategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryService categoryService;

    @Test
    @DisplayName("활성 카테고리는 true")
    void isActive_returnsTrueForActiveCategory() {
        // given
        Category category = Category.builder().code("FASHION").name("패션").active(true).build();
        given(categoryRepository.findById(1L)).willReturn(Optional.of(category));

        // when & then
        assertThat(categoryService.isActive(1L)).isTrue();
    }

    @Test
    @DisplayName("비활성 카테고리는 false")
    void isActive_returnsFalseForInactiveCategory() {
        // given
        Category category = Category.builder().code("FASHION").name("패션").active(false).build();
        given(categoryRepository.findById(1L)).willReturn(Optional.of(category));

        // when & then
        assertThat(categoryService.isActive(1L)).isFalse();
    }

    @Test
    @DisplayName("없는 카테고리는 false")
    void isActive_returnsFalseForMissingCategory() {
        // given
        given(categoryRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        assertThat(categoryService.isActive(1L)).isFalse();
    }
}
