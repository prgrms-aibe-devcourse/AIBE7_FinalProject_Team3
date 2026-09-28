package org.example.grab.domain.category.controller;

import org.example.grab.domain.category.dto.response.CategoryResponse;
import org.example.grab.domain.category.service.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CategoryControllerTest {

    private CategoryService categoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        categoryService = mock(CategoryService.class);
        CategoryController controller = new CategoryController(categoryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("카테고리 목록은 200과 categoryId·code·name 배열을 반환한다")
    void listCategories() throws Exception {
        // given
        given(categoryService.findActiveCategories()).willReturn(List.of(
                new CategoryResponse(1L, "FASHION", "패션"),
                new CategoryResponse(2L, "SHOES", "신발")));

        // when & then
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].categoryId").value(1))
                .andExpect(jsonPath("$.data[0].code").value("FASHION"))
                .andExpect(jsonPath("$.data[0].name").value("패션"))
                .andExpect(jsonPath("$.data[1].categoryId").value(2))
                .andExpect(jsonPath("$.data[1].code").value("SHOES"));
    }

    @Test
    @DisplayName("활성 카테고리가 없으면 200과 빈 배열을 반환한다")
    void listCategories_empty() throws Exception {
        // given
        given(categoryService.findActiveCategories()).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
