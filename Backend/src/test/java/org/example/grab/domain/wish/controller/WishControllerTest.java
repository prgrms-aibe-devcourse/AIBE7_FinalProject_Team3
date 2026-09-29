package org.example.grab.domain.wish.controller;

import org.example.grab.domain.wish.WishNotice;
import org.example.grab.domain.wish.dto.response.WishResponse;
import org.example.grab.domain.wish.service.WishService;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.security.CurrentUserIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WishControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long DROP_ID = 100L;

    private WishService wishService;
    private CurrentUserIdProvider currentUserIdProvider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        wishService = mock(WishService.class);
        currentUserIdProvider = mock(CurrentUserIdProvider.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WishController(wishService, currentUserIdProvider))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("WISH 등록은 200과 DROP ID·wished·등록 시각·안내 문구를 반환한다")
    void registersWish() throws Exception {
        // given
        OffsetDateTime wishedAt = OffsetDateTime.parse("2026-09-18T14:00:00+09:00");
        given(currentUserIdProvider.currentUserId()).willReturn(USER_ID);
        given(wishService.register(USER_ID, DROP_ID))
                .willReturn(new WishResponse(DROP_ID, true, wishedAt, WishNotice.MESSAGE));

        // when & then
        mockMvc.perform(put("/api/v1/drops/{dropId}/wish", DROP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dropId").value(DROP_ID))
                .andExpect(jsonPath("$.data.wished").value(true))
                .andExpect(jsonPath("$.data.wishedAt").value("2026-09-18T14:00:00+09:00"))
                .andExpect(jsonPath("$.data.notice").value(WishNotice.MESSAGE));
    }

    @Test
    @DisplayName("WISH 취소는 본문 없이 204를 반환한다")
    void cancelsWish() throws Exception {
        // given
        given(currentUserIdProvider.currentUserId()).willReturn(USER_ID);

        // when & then
        mockMvc.perform(delete("/api/v1/drops/{dropId}/wish", DROP_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        then(wishService).should().cancel(USER_ID, DROP_ID);
    }

    @Test
    @DisplayName("숫자가 아닌 DROP ID는 400 INVALID_REQUEST")
    void rejectsNonNumericDropId() throws Exception {
        mockMvc.perform(put("/api/v1/drops/{dropId}/wish", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("인증 정보가 없으면 401 AUTHENTICATION_REQUIRED")
    void requiresAuthentication() throws Exception {
        // given
        given(currentUserIdProvider.currentUserId())
                .willThrow(new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));

        // when & then
        mockMvc.perform(put("/api/v1/drops/{dropId}/wish", DROP_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }
}
