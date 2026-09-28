package org.example.grab.domain.category.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.category.dto.response.CategoryResponse;
import org.example.grab.domain.category.entity.Category;
import org.example.grab.domain.category.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public boolean isActive(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .map(Category::isActive)
                .orElse(false);
    }

    public List<CategoryResponse> findActiveCategories() {
        return categoryRepository.findAllByActiveTrueOrderByIdAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }
}
