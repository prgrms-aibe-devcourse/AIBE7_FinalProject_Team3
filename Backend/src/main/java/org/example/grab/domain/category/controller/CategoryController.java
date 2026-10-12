package org.example.grab.domain.category.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.category.dto.response.CategoryResponse;
import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.global.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
@Tag(name = "카테고리", description = "DROP 분류용 카테고리 조회")
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "카테고리 목록 조회", description = "활성 카테고리만 반환한다. 로그인 없이 호출한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public ApiResponse<List<CategoryResponse>> listCategories() {
        return ApiResponse.success(categoryService.findActiveCategories());
    }
}
