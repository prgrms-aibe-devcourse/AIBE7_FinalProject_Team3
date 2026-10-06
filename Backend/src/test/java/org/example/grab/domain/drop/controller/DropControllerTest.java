package org.example.grab.domain.drop.controller;

import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse;
import org.example.grab.domain.drop.dto.response.PublicDropListResponse;
import org.example.grab.domain.drop.dto.response.PublicDropStockResponse;
import org.example.grab.domain.drop.dto.response.common.DropCategoryResponse;
import org.example.grab.domain.drop.dto.response.common.DropOptionGroupResponse;
import org.example.grab.domain.drop.dto.response.common.DropOptionSelectionResponse;
import org.example.grab.domain.drop.dto.response.common.DropShippingResponse;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
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
                new DropCategoryResponse(1L, "패션"),
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

    @Test
    @DisplayName("공개 DROP 상세는 200과 명세 1.3 필드를 반환한다")
    void getDrop() throws Exception {
        // given
        PublicDropDetailResponse response = new PublicDropDetailResponse(
                100L, "상품", "설명", List.of("https://img/1.jpg"), 129000L,
                new DropCategoryResponse(1L, "패션"),
                DropStatus.GRAB, false, 7L, "WISH 안내 문구",
                OffsetDateTime.parse("2026-09-20T01:00:00Z"),
                OffsetDateTime.parse("2026-09-20T03:00:00Z"),
                new DropShippingResponse(3000L, "출고 안내"),
                List.of(new DropOptionGroupResponse(11L, "소재", 0,
                        List.of(new DropOptionGroupResponse.Value(111L, "코튼", 0)))),
                List.of(new PublicDropDetailResponse.Option(1001L,
                        List.of(new DropOptionSelectionResponse(11L, 111L)), 129000L, 10, false)),
                new PublicDropDetailResponse.Actions(false, false, true),
                OffsetDateTime.parse("2026-09-20T01:10:00Z"));
        given(dropService.findPublicDrop(100L)).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/drops/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dropId").value(100))
                .andExpect(jsonPath("$.data.name").value("상품"))
                .andExpect(jsonPath("$.data.description").value("설명"))
                .andExpect(jsonPath("$.data.imageUrls[0]").value("https://img/1.jpg"))
                .andExpect(jsonPath("$.data.minPrice").value(129000))
                .andExpect(jsonPath("$.data.category.categoryId").value(1))
                .andExpect(jsonPath("$.data.category.name").value("패션"))
                .andExpect(jsonPath("$.data.status").value("GRAB"))
                .andExpect(jsonPath("$.data.soldOut").value(false))
                .andExpect(jsonPath("$.data.wishCount").value(7))
                .andExpect(jsonPath("$.data.wishNotice").value("WISH 안내 문구"))
                .andExpect(jsonPath("$.data.saleStartsAt").exists())
                .andExpect(jsonPath("$.data.saleEndsAt").exists())
                .andExpect(jsonPath("$.data.shipping.shippingFee").value(3000))
                .andExpect(jsonPath("$.data.shipping.shippingNotice").value("출고 안내"))
                .andExpect(jsonPath("$.data.optionGroups[0].groupId").value(11))
                .andExpect(jsonPath("$.data.optionGroups[0].name").value("소재"))
                .andExpect(jsonPath("$.data.optionGroups[0].values[0].valueId").value(111))
                .andExpect(jsonPath("$.data.optionGroups[0].values[0].value").value("코튼"))
                .andExpect(jsonPath("$.data.options[0].optionId").value(1001))
                .andExpect(jsonPath("$.data.options[0].selections[0].groupId").value(11))
                .andExpect(jsonPath("$.data.options[0].unitPrice").value(129000))
                .andExpect(jsonPath("$.data.options[0].availableStock").value(10))
                .andExpect(jsonPath("$.data.options[0].soldOut").value(false))
                .andExpect(jsonPath("$.data.actions.wishable").value(false))
                .andExpect(jsonPath("$.data.actions.wishCancelable").value(false))
                .andExpect(jsonPath("$.data.actions.orderable").value(true))
                .andExpect(jsonPath("$.data.serverTime").exists());
    }

    @Test
    @DisplayName("공개 재고 재조회는 200과 명세 1.2 필드만 반환한다")
    void getStocks() throws Exception {
        // given
        PublicDropStockResponse response = new PublicDropStockResponse(
                100L,
                List.of(new PublicDropStockResponse.Option(1001L, 8, false),
                        new PublicDropStockResponse.Option(1002L, 0, true)),
                OffsetDateTime.parse("2026-10-03T05:00:00Z"));
        given(dropService.findPublicStocks(100L)).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/drops/100/stocks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dropId").value(100))
                .andExpect(jsonPath("$.data.options[0].optionId").value(1001))
                .andExpect(jsonPath("$.data.options[0].availableStock").value(8))
                .andExpect(jsonPath("$.data.options[0].soldOut").value(false))
                .andExpect(jsonPath("$.data.options[1].optionId").value(1002))
                .andExpect(jsonPath("$.data.options[1].soldOut").value(true))
                .andExpect(jsonPath("$.data.serverTime").exists())
                // 판매자용 내부 수량 필드는 공개 응답에 없다
                .andExpect(jsonPath("$.data.options[0].totalStock").doesNotExist())
                .andExpect(jsonPath("$.data.options[0].reservedStock").doesNotExist())
                .andExpect(jsonPath("$.data.options[0].soldStock").doesNotExist())
                .andExpect(jsonPath("$.data.options[0].optionName").doesNotExist());
    }

    @Test
    @DisplayName("공개 재고 재조회: DRAFT면 404 DROP_NOT_FOUND")
    void getStocks_hidesDraft() throws Exception {
        given(dropService.findPublicStocks(99L))
                .willThrow(new BusinessException(DropErrorCode.DROP_NOT_FOUND));

        mockMvc.perform(get("/api/v1/drops/99/stocks"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DROP_NOT_FOUND"));
    }

    @Test
    @DisplayName("dropId가 숫자가 아니면 400 INVALID_REQUEST")
    void rejectsNonNumericDropId() throws Exception {
        mockMvc.perform(get("/api/v1/drops/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(dropService);
    }

    @Test
    @DisplayName("서비스가 DROP_NOT_FOUND를 던지면 404")
    void returnsNotFoundForMissingDrop() throws Exception {
        given(dropService.findPublicDrop(99L))
                .willThrow(new BusinessException(DropErrorCode.DROP_NOT_FOUND));

        mockMvc.perform(get("/api/v1/drops/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DROP_NOT_FOUND"));
    }
}
