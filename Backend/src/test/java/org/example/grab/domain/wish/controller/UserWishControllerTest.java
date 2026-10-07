package org.example.grab.domain.wish.controller;

import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.wish.dto.response.WishListResponse;
import org.example.grab.domain.wish.service.WishQueryService;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserWishControllerTest {

    private static final Long USER_ID = 1L;

    private WishQueryService wishQueryService;
    private CurrentUserIdProvider currentUserIdProvider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        wishQueryService = mock(WishQueryService.class);
        currentUserIdProvider = mock(CurrentUserIdProvider.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UserWishController(wishQueryService, currentUserIdProvider))
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
                .build();
    }

    @Test
    @DisplayName("내 WISH 목록은 200과 페이지 형식·항목 필드를 반환한다")
    void listMyWishes() throws Exception {
        // given
        given(currentUserIdProvider.currentUserId()).willReturn(USER_ID);
        WishListResponse item = new WishListResponse(100L, "한정판 스니커즈",
                "https://img/1.jpg", 129000L, DropStatus.WISH,
                OffsetDateTime.parse("2026-09-18T14:00:00Z"));
        given(wishQueryService.findMyWishes(eq(USER_ID), eq(0), eq(20)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1, false));

        // when & then
        mockMvc.perform(get("/api/v1/users/me/wishes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].dropId").value(100))
                .andExpect(jsonPath("$.data.content[0].name").value("한정판 스니커즈"))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value("https://img/1.jpg"))
                .andExpect(jsonPath("$.data.content[0].minPrice").value(129000))
                .andExpect(jsonPath("$.data.content[0].status").value("WISH"))
                .andExpect(jsonPath("$.data.content[0].wishedAt").exists())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("page=-1이면 400 INVALID_REQUEST")
    void rejectsNegativePage() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/wishes").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(wishQueryService);
    }

    @Test
    @DisplayName("size가 1 미만이거나 100을 넘으면 400 INVALID_REQUEST")
    void rejectsInvalidSize() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/wishes").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/users/me/wishes").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(wishQueryService);
    }

    @Test
    @DisplayName("비로그인 요청은 401 AUTHENTICATION_REQUIRED")
    void rejectsUnauthenticated() throws Exception {
        // given
        given(currentUserIdProvider.currentUserId())
                .willThrow(new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));

        // when & then
        mockMvc.perform(get("/api/v1/users/me/wishes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }
}
