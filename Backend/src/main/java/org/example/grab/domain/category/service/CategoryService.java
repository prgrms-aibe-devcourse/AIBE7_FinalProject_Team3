package org.example.grab.domain.category.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.category.dto.response.CategoryResponse;
import org.example.grab.domain.category.entity.Category;
import org.example.grab.domain.category.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

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

    // 공개 DROP 상세가 카테고리 이름을 얻는 창구. 비활성 카테고리도 이름을 그대로 반환한다(목록과 같은 노출 기준).
    public Optional<CategoryResponse> findCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .map(CategoryResponse::from);
    }

    public List<CategoryResponse> findActiveCategories() {
        return categoryRepository.findAllByActiveTrueOrderByIdAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }
}
