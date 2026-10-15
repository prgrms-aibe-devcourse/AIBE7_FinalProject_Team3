package org.example.grab.domain.seller.controller;

import org.example.grab.domain.seller.dto.AdminSellerApplicationResponse;
import org.example.grab.domain.seller.dto.SellerApplicationApprovalResponse;
import org.example.grab.domain.seller.dto.SellerApplicationRejectionResponse;
import org.example.grab.domain.seller.dto.SellerDocumentView;
import org.example.grab.domain.seller.entity.DocumentType;
import org.example.grab.domain.seller.entity.SellerStatus;
import org.example.grab.domain.seller.error.SellerErrorCode;
import org.example.grab.domain.seller.service.AdminSellerApplicationService;
import org.example.grab.domain.seller.service.SellerDocumentViewService;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminSellerApplicationControllerTest {

    private final AdminSellerApplicationService adminSellerApplicationService = mock(AdminSellerApplicationService.class);
    private final SellerDocumentViewService sellerDocumentViewService = mock(SellerDocumentViewService.class);
    private final CurrentUserIdProvider currentUserIdProvider = mock(CurrentUserIdProvider.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AdminSellerApplicationController(
                    adminSellerApplicationService, sellerDocumentViewService, currentUserIdProvider))
            .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
            .build();

    @Test
    @DisplayName("상태와 페이지를 넘기면 신청 목록을 페이지 응답으로 반환한다")
    void findApplications_withStatus() throws Exception {
        AdminSellerApplicationResponse item = new AdminSellerApplicationResponse(
                UUID.randomUUID(), UUID.randomUUID(), "길동상점", "seller@example.com", "1234567890",
                SellerStatus.PENDING, OffsetDateTime.parse("2026-09-18T14:00:00+09:00"));
        given(adminSellerApplicationService.findApplications(SellerStatus.PENDING, 0, 20))
                .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1, false));

        mockMvc.perform(get("/api/v1/admin/seller-applications").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].brandName").value("길동상점"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        verify(adminSellerApplicationService).findApplications(SellerStatus.PENDING, 0, 20);
    }

    @Test
    @DisplayName("상태를 생략하면 전체 상태를 기본 페이지로 조회한다")
    void findApplications_withoutStatus() throws Exception {
        given(adminSellerApplicationService.findApplications(null, 0, 20))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, false));

        mockMvc.perform(get("/api/v1/admin/seller-applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty());
        verify(adminSellerApplicationService).findApplications(null, 0, 20);
    }

    @Test
    @DisplayName("허용 범위를 벗어난 페이지 값은 400 INVALID_REQUEST로 거부한다")
    void findApplications_rejectsInvalidPaging() throws Exception {
        mockMvc.perform(get("/api/v1/admin/seller-applications").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/admin/seller-applications").param("size", "101"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adminSellerApplicationService);
    }

    @Test
    @DisplayName("승인 요청을 받으면 승인 결과를 반환한다")
    void approve() throws Exception {
        UUID applicationId = UUID.randomUUID();
        given(currentUserIdProvider.currentUserId()).willReturn(100L);
        given(adminSellerApplicationService.approve(applicationId, 100L)).willReturn(
                new SellerApplicationApprovalResponse(applicationId, SellerStatus.APPROVED,
                        OffsetDateTime.parse("2026-09-18T15:00:00+09:00")));

        mockMvc.perform(post("/api/v1/admin/seller-applications/{applicationId}/approve", applicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        verify(adminSellerApplicationService).approve(applicationId, 100L);
    }

    @Test
    @DisplayName("존재하지 않는 신청을 승인하면 404로 응답한다")
    void approve_notFound() throws Exception {
        UUID applicationId = UUID.randomUUID();
        given(currentUserIdProvider.currentUserId()).willReturn(100L);
        given(adminSellerApplicationService.approve(applicationId, 100L))
                .willThrow(new BusinessException(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND));

        mockMvc.perform(post("/api/v1/admin/seller-applications/{applicationId}/approve", applicationId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SELLER_APPLICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("반려 요청에 사유를 담아 보내면 반려 결과를 반환한다")
    void reject() throws Exception {
        UUID applicationId = UUID.randomUUID();
        given(currentUserIdProvider.currentUserId()).willReturn(100L);
        given(adminSellerApplicationService.reject(applicationId, 100L, "서류 미비")).willReturn(
                new SellerApplicationRejectionResponse(applicationId, SellerStatus.REJECTED, "서류 미비",
                        OffsetDateTime.parse("2026-09-18T15:00:00+09:00")));

        mockMvc.perform(post("/api/v1/admin/seller-applications/{applicationId}/reject", applicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"서류 미비"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rejectedReason").value("서류 미비"));
        verify(adminSellerApplicationService).reject(applicationId, 100L, "서류 미비");
    }

    @Test
    @DisplayName("반려 사유가 없거나 공백이면 400 VALIDATION_FAILED로 거부한다")
    void reject_rejectsBlankReason() throws Exception {
        UUID applicationId = UUID.randomUUID();
        for (String body : List.of("{}", "{\"reason\":\" \"}")) {
            mockMvc.perform(post("/api/v1/admin/seller-applications/{applicationId}/reject", applicationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(adminSellerApplicationService);
    }

    @Test
    @DisplayName("서류 열람 요청은 이미지 바이트를 캐시 금지 헤더와 함께 반환한다")
    void viewDocument() throws Exception {
        UUID applicationId = UUID.randomUUID();
        byte[] imageBytes = {1, 2, 3};
        given(currentUserIdProvider.currentUserId()).willReturn(100L);
        given(sellerDocumentViewService.view(applicationId, DocumentType.BUSINESS_LICENSE, 100L))
                .willReturn(new SellerDocumentView(imageBytes, "image/png"));

        mockMvc.perform(get("/api/v1/admin/seller-applications/{applicationId}/documents/{documentType}/view",
                        applicationId, DocumentType.BUSINESS_LICENSE))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(imageBytes));
        verify(sellerDocumentViewService).view(applicationId, DocumentType.BUSINESS_LICENSE, 100L);
    }

    @Test
    @DisplayName("신청이나 해당 유형의 서류가 없으면 404로 응답한다")
    void viewDocument_notFound() throws Exception {
        UUID applicationId = UUID.randomUUID();
        given(currentUserIdProvider.currentUserId()).willReturn(100L);
        given(sellerDocumentViewService.view(applicationId, DocumentType.ID_CARD, 100L))
                .willThrow(new BusinessException(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/admin/seller-applications/{applicationId}/documents/{documentType}/view",
                        applicationId, DocumentType.ID_CARD))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SELLER_APPLICATION_NOT_FOUND"));
    }
}
