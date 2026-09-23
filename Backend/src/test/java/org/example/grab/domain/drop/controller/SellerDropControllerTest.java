package org.example.grab.domain.drop.controller;

import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.security.CurrentSellerIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellerDropControllerTest {

    private DropService dropService;
    private CurrentSellerIdProvider currentSellerIdProvider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dropService = mock(DropService.class);
        currentSellerIdProvider = mock(CurrentSellerIdProvider.class);
        SellerDropController controller = new SellerDropController(dropService, currentSellerIdProvider);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("임시 저장은 201과 dropId·status를 반환한다")
    void createDraft() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropService.createDraft(eq(1L), any(DropDraftRequest.class))).willReturn(draftWithId(100L));

        // when & then
        mockMvc.perform(post("/api/v1/seller/drops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"상품\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dropId").value(100))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    @DisplayName("DRAFT 수정은 200과 상태를 반환한다")
    void updateDraft() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropService.updateDraft(eq(1L), eq(100L), any(DropDraftRequest.class))).willReturn(draftWithId(100L));

        // when & then
        mockMvc.perform(patch("/api/v1/seller/drops/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"수정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dropId").value(100));
    }

    @Test
    @DisplayName("요청 검증에 실패하면 400 VALIDATION_FAILED를 반환한다")
    void createDraft_validationFailed() throws Exception {
        // given
        String longUrl = "x".repeat(501);

        // when & then
        mockMvc.perform(post("/api/v1/seller/drops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrls\":[\"" + longUrl + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("인증되지 않은 요청은 401 AUTHENTICATION_REQUIRED")
    void createDraft_authenticationRequired() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId())
                .willThrow(new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED));

        // when & then
        mockMvc.perform(post("/api/v1/seller/drops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("SELLER 권한이 없으면 403 ACCESS_DENIED")
    void createDraft_accessDenied() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId())
                .willThrow(new BusinessException(CommonErrorCode.ACCESS_DENIED));

        // when & then
        mockMvc.perform(post("/api/v1/seller/drops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("없는 dropId 수정은 404 DROP_NOT_FOUND와 ErrorResponse 형식을 반환한다")
    void updateDraft_notFound() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropService.updateDraft(eq(1L), eq(999L), any(DropDraftRequest.class)))
                .willThrow(new BusinessException(DropErrorCode.DROP_NOT_FOUND));

        // when & then
        mockMvc.perform(patch("/api/v1/seller/drops/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("DROP_NOT_FOUND"))
                .andExpect(jsonPath("$.error.fieldErrors").isEmpty());
    }

    @Test
    @DisplayName("다른 판매자의 DROP 수정은 403 DROP_ACCESS_DENIED")
    void updateDraft_accessDenied() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropService.updateDraft(eq(1L), eq(100L), any(DropDraftRequest.class)))
                .willThrow(new BusinessException(DropErrorCode.DROP_ACCESS_DENIED));

        // when & then
        mockMvc.perform(patch("/api/v1/seller/drops/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DROP_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("DRAFT가 아닌 DROP 수정은 409 DROP_NOT_EDITABLE")
    void updateDraft_notEditable() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropService.updateDraft(eq(1L), eq(100L), any(DropDraftRequest.class)))
                .willThrow(new BusinessException(DropErrorCode.DROP_NOT_EDITABLE));

        // when & then
        mockMvc.perform(patch("/api/v1/seller/drops/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DROP_NOT_EDITABLE"));
    }

    private Drop draftWithId(Long id) {
        Drop drop = Drop.createDraft(1L);
        ReflectionTestUtils.setField(drop, "id", id);
        return drop;
    }
}
