package org.example.grab.domain.drop.controller;

import org.example.grab.domain.drop.dto.response.PublicDropListResponse;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DropControllerTest {

    private DropService dropService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dropService = mock(DropService.class);
        DropController controller = new DropController(dropService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
                .build();
    }

    @Test
    @DisplayName("공개 DROP 목록은 200과 페이지 형식·중첩 category를 반환한다")
    void listDrops() throws Exception {
        // given
        PublicDropListResponse item = new PublicDropListResponse(
                100L, "상품", "https://img/1.jpg", 129000L,
                new PublicDropListResponse.Category(1L, "패션"),
                DropStatus.WISH, false, 152L,
                OffsetDateTime.parse("2026-09-20T01:00:00Z"),
                OffsetDateTime.parse("2026-09-20T03:00:00Z"));
        given(dropService.findPublicDrops(any(), any(), any(), any(), any(), eq(0), eq(20)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1, false));

        // when & then
        mockMvc.perform(get("/api/v1/drops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].dropId").value(100))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value("https://img/1.jpg"))
                .andExpect(jsonPath("$.data.content[0].minPrice").value(129000))
                .andExpect(jsonPath("$.data.content[0].category.categoryId").value(1))
                .andExpect(jsonPath("$.data.content[0].category.name").value("패션"))
                .andExpect(jsonPath("$.data.content[0].status").value("WISH"))
                .andExpect(jsonPath("$.data.content[0].soldOut").value(false))
                .andExpect(jsonPath("$.data.content[0].wishCount").value(152))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("page=-1이면 400 INVALID_REQUEST")
    void rejectsNegativePage() throws Exception {
        mockMvc.perform(get("/api/v1/drops").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(dropService);
    }

    @Test
    @DisplayName("size가 1 미만이거나 100을 넘으면 400 INVALID_REQUEST")
    void rejectsInvalidSize() throws Exception {
        mockMvc.perform(get("/api/v1/drops").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/drops").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(dropService);
    }

    @Test
    @DisplayName("허용되지 않은 sort는 400 INVALID_REQUEST")
    void rejectsInvalidSort() throws Exception {
        mockMvc.perform(get("/api/v1/drops").param("sort", "name,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/drops").param("sort", "createdAt,sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(dropService);
    }

    @Test
    @DisplayName("status=DRAFT·CANCELED는 400 INVALID_REQUEST")
    void rejectsNonPublicStatus() throws Exception {
        mockMvc.perform(get("/api/v1/drops").param("status", "DRAFT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/drops").param("status", "CANCELED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(dropService);
    }

    @Test
    @DisplayName("status=FOO·categoryId=abc는 400 INVALID_REQUEST")
    void rejectsMalformedParams() throws Exception {
        mockMvc.perform(get("/api/v1/drops").param("status", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/drops").param("categoryId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(dropService);
    }
}
