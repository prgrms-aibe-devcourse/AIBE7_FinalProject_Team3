package org.example.grab.domain.category.dto.response;

import org.example.grab.domain.category.entity.Category;

public record CategoryResponse(
        Long categoryId,
        String code,
        String name
) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getCode(), category.getName());
    }
}
