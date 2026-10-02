package org.example.grab.domain.drop.controller;

import org.example.grab.domain.drop.dto.request.DropDraftRequest;
import org.example.grab.domain.drop.dto.response.SellerDropDetailResponse;
import org.example.grab.domain.drop.dto.response.SellerDropListResponse;
import org.example.grab.domain.drop.dto.response.common.DropOptionGroupResponse;
import org.example.grab.domain.drop.dto.response.common.DropOptionSelectionResponse;
import org.example.grab.domain.drop.dto.response.common.DropShippingResponse;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
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
                        .content("{\"images\":[{\"imageId\":\"" + java.util.UUID.randomUUID()
                                + "\",\"imageUrl\":\"" + longUrl + "\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("중첩 배열의 null 원소는 400 VALIDATION_FAILED와 경로 field를 반환한다")
    void createDraft_rejectsNullElementsInNestedArrays() throws Exception {
        // given: 옵션 조합 표시명 검증에서 NPE로 500이 나던 입력들
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);

        // when & then
        mockMvc.perform(post("/api/v1/seller/drops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionGroups\":[null],\"options\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("optionGroups[0]"));

        mockMvc.perform(post("/api/v1/seller/drops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionGroups\":[],\"options\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("options[0]"));

        mockMvc.perform(post("/api/v1/seller/drops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionGroups\":[{\"key\":\"c\",\"name\":\"색상\",\"values\":[null]}],"
                                + "\"options\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("optionGroups[0].values[0]"));

        mockMvc.perform(post("/api/v1/seller/drops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionGroups\":[],\"options\":[{\"selections\":[null],"
                                + "\"unitPrice\":1000,\"totalQuantity\":5}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("options[0].selections[0]"));

        // 검증 단계에서 거부되어 서비스(DB 변경)가 호출되지 않는다
        verifyNoInteractions(dropService);
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

    @Test
    @DisplayName("공개는 200과 WISH·publishedAt을 반환한다")
    void publish() throws Exception {
        // given
        Drop drop = draftWithId(100L);
        ReflectionTestUtils.setField(drop, "status", DropStatus.WISH);
        ReflectionTestUtils.setField(drop, "publishedAt", OffsetDateTime.parse("2026-09-18T07:00:00Z"));
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropService.publish(1L, 100L)).willReturn(drop);

        // when & then
        mockMvc.perform(post("/api/v1/seller/drops/100/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dropId").value(100))
                .andExpect(jsonPath("$.data.status").value("WISH"))
                .andExpect(jsonPath("$.data.publishedAt").exists());
    }

    @Test
    @DisplayName("공개 검증 실패는 오류 코드와 위반 항목을 fieldErrors로 반환한다")
    void publish_validationFailed() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropService.publish(1L, 100L)).willThrow(new BusinessException(
                DropErrorCode.DUPLICATE_OPTION_COMBINATION,
                List.of(new ErrorResponse.FieldError("options[1]", "동일한 옵션값 조합의 SKU가 이미 있습니다."))));

        // when & then
        mockMvc.perform(post("/api/v1/seller/drops/100/publish"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_OPTION_COMBINATION"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("options[1]"));
    }

    @Test
    @DisplayName("취소는 200과 CANCELED·canceledAt을 반환한다")
    void cancel() throws Exception {
        // given
        Drop drop = draftWithId(100L);
        ReflectionTestUtils.setField(drop, "status", DropStatus.CANCELED);
        ReflectionTestUtils.setField(drop, "closedAt", OffsetDateTime.parse("2026-09-18T07:00:00Z"));
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropService.cancel(1L, 100L, "재고 확보 실패")).willReturn(drop);

        // when & then
        mockMvc.perform(post("/api/v1/seller/drops/100/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"재고 확보 실패\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dropId").value(100))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.canceledAt").exists());
    }

    @Test
    @DisplayName("취소 사유가 없거나 공백이거나 500자를 넘으면 400 VALIDATION_FAILED")
    void cancel_rejectsInvalidReason() throws Exception {
        // when & then
        mockMvc.perform(post("/api/v1/seller/drops/100/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/seller/drops/100/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/seller/drops/100/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(dropService);
    }

    @Test
    @DisplayName("없는 DROP 취소는 404, 다른 판매자 DROP은 403, 취소 불가 상태는 409")
    void cancel_errorMappings() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropService.cancel(1L, 999L, "사유"))
                .willThrow(new BusinessException(DropErrorCode.DROP_NOT_FOUND));
        given(dropService.cancel(1L, 100L, "사유"))
                .willThrow(new BusinessException(DropErrorCode.DROP_ACCESS_DENIED));
        given(dropService.cancel(1L, 101L, "사유"))
                .willThrow(new BusinessException(CommonErrorCode.INVALID_STATE_TRANSITION));

        // when & then
        mockMvc.perform(post("/api/v1/seller/drops/999/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"사유\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DROP_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/seller/drops/100/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"사유\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DROP_ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/seller/drops/101/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"사유\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("판매자 DROP 목록은 페이지 형식으로 반환한다")
    void listDrops() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        SellerDropListResponse item = new SellerDropListResponse(100L, "상품", "https://example.com/image.jpg", DropStatus.DRAFT, 10000L,
                OffsetDateTime.parse("2026-09-18T07:00:00Z"));
        given(dropService.findSellerDrops(eq(1L), any(), eq(0), eq(20)))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1, false));

        // when & then
        mockMvc.perform(get("/api/v1/seller/drops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].dropId").value(100))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value("https://example.com/image.jpg"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("page=-1이면 400 INVALID_REQUEST")
    void listDrops_rejectsNegativePage() throws Exception {
        mockMvc.perform(get("/api/v1/seller/drops").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(dropService);
    }

    @Test
    @DisplayName("size가 1 미만이거나 100을 넘으면 400 INVALID_REQUEST")
    void listDrops_rejectsInvalidSize() throws Exception {
        mockMvc.perform(get("/api/v1/seller/drops").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/seller/drops").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(dropService);
    }

    @Test
    @DisplayName("status=FOO이면 400 INVALID_REQUEST")
    void listDrops_rejectsInvalidStatus() throws Exception {
        mockMvc.perform(get("/api/v1/seller/drops").param("status", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(dropService);
    }

    @Test
    @DisplayName("판매자 DROP 상세는 옵션 그룹·값·SKU 구조로 반환한다")
    void findDrop() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        SellerDropDetailResponse detail = new SellerDropDetailResponse(
                100L, "상품", "설명",
                List.of(new SellerDropDetailResponse.Image(java.util.UUID.randomUUID(), "https://example.com/a.jpg")),
                10000L, 1L, DropStatus.DRAFT,
                OffsetDateTime.parse("2026-09-18T07:00:00Z"), OffsetDateTime.parse("2026-09-18T09:00:00Z"),
                new DropShippingResponse(3000L, "안내"),
                List.of(new DropOptionGroupResponse(11L, "소재", 0,
                        List.of(new DropOptionGroupResponse.Value(111L, "코튼", 0)))),
                List.of(new SellerDropDetailResponse.Option(1001L,
                        List.of(new DropOptionSelectionResponse(11L, 111L)),
                        129000L, 10, 0, 0, true, 0)));
        given(dropService.findSellerDrop(1L, 100L)).willReturn(detail);

        // when & then
        mockMvc.perform(get("/api/v1/seller/drops/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dropId").value(100))
                .andExpect(jsonPath("$.data.shipping.shippingFee").value(3000))
                .andExpect(jsonPath("$.data.optionGroups[0].groupId").value(11))
                .andExpect(jsonPath("$.data.optionGroups[0].values[0].valueId").value(111))
                .andExpect(jsonPath("$.data.options[0].selections[0].valueId").value(111));
    }

    @Test
    @DisplayName("없는 dropId 상세 조회는 404 DROP_NOT_FOUND")
    void findDrop_notFound() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropService.findSellerDrop(1L, 999L))
                .willThrow(new BusinessException(DropErrorCode.DROP_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/v1/seller/drops/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DROP_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 판매자의 DROP 상세 조회는 403 DROP_ACCESS_DENIED")
    void findDrop_accessDenied() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropService.findSellerDrop(1L, 100L))
                .willThrow(new BusinessException(DropErrorCode.DROP_ACCESS_DENIED));

        // when & then
        mockMvc.perform(get("/api/v1/seller/drops/100"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("DROP_ACCESS_DENIED"));
    }

    private Drop draftWithId(Long id) {
        Drop drop = Drop.createDraft(1L);
        ReflectionTestUtils.setField(drop, "id", id);
        return drop;
    }
}
