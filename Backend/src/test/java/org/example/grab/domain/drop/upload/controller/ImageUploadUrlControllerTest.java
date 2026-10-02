package org.example.grab.domain.drop.upload.controller;

import org.example.grab.domain.drop.upload.dto.ImageUploadUrlResponse;
import org.example.grab.domain.drop.upload.service.DropImageUploadService;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImageUploadUrlControllerTest {

    private DropImageUploadService dropImageUploadService;
    private CurrentSellerIdProvider currentSellerIdProvider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dropImageUploadService = mock(DropImageUploadService.class);
        currentSellerIdProvider = mock(CurrentSellerIdProvider.class);
        ImageUploadUrlController controller = new ImageUploadUrlController(dropImageUploadService, currentSellerIdProvider);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
                .build();
    }

    @Test
    @DisplayName("SELLER 정상 발급은 200과 imageId·uploadUrl·imageUrl·expiresAt을 반환한다")
    void issuePresignedUrl() throws Exception {
        // given
        UUID imageId = UUID.randomUUID();
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropImageUploadService.issue("image/jpeg", 1048576L)).willReturn(new ImageUploadUrlResponse(
                imageId, "https://project.supabase.co/signed", "https://project.supabase.co/public/image.jpg",
                OffsetDateTime.parse("2026-09-18T16:10:00+09:00")));

        // when & then
        mockMvc.perform(post("/api/v1/uploads/images/presigned-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"shoes.jpg\",\"contentType\":\"image/jpeg\",\"fileSize\":1048576}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.imageId").value(imageId.toString()))
                .andExpect(jsonPath("$.data.uploadUrl").value("https://project.supabase.co/signed"))
                .andExpect(jsonPath("$.data.imageUrl").value("https://project.supabase.co/public/image.jpg"))
                .andExpect(jsonPath("$.data.expiresAt").exists());
    }

    @Test
    @DisplayName("fileName 누락·255자 초과는 400 VALIDATION_FAILED(fileName)이고 서비스를 호출하지 않는다")
    void rejectsInvalidFileName() throws Exception {
        // when & then
        mockMvc.perform(post("/api/v1/uploads/images/presigned-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"fileSize\":1048576}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("fileName"));
        mockMvc.perform(post("/api/v1/uploads/images/presigned-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"" + "x".repeat(256) + "\",\"contentType\":\"image/jpeg\",\"fileSize\":1048576}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("fileName"));
        verifyNoInteractions(dropImageUploadService, currentSellerIdProvider);
    }

    @Test
    @DisplayName("contentType 누락은 400 VALIDATION_FAILED(contentType)")
    void rejectsMissingContentType() throws Exception {
        mockMvc.perform(post("/api/v1/uploads/images/presigned-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"shoes.jpg\",\"fileSize\":1048576}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("contentType"));
    }

    @Test
    @DisplayName("fileSize 누락·0 이하는 400 VALIDATION_FAILED(fileSize)")
    void rejectsInvalidFileSize() throws Exception {
        mockMvc.perform(post("/api/v1/uploads/images/presigned-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"shoes.jpg\",\"contentType\":\"image/jpeg\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("fileSize"));
        mockMvc.perform(post("/api/v1/uploads/images/presigned-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"shoes.jpg\",\"contentType\":\"image/jpeg\",\"fileSize\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("fileSize"));
    }

    @Test
    @DisplayName("비로그인은 401 AUTHENTICATION_REQUIRED, USER는 403 ACCESS_DENIED")
    void rejectsNotSeller() throws Exception {
        // given: 같은 mock을 다시 스텁하므로 given()이 아니라 doThrow()를 쓴다
        doThrow(new BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED))
                .when(currentSellerIdProvider).currentSellerId();

        // when & then
        mockMvc.perform(post("/api/v1/uploads/images/presigned-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"shoes.jpg\",\"contentType\":\"image/jpeg\",\"fileSize\":1048576}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        doThrow(new BusinessException(CommonErrorCode.ACCESS_DENIED))
                .when(currentSellerIdProvider).currentSellerId();
        mockMvc.perform(post("/api/v1/uploads/images/presigned-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"shoes.jpg\",\"contentType\":\"image/jpeg\",\"fileSize\":1048576}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Supabase 발급 실패는 502 EXTERNAL_SERVICE_ERROR")
    void mapsExternalFailure() throws Exception {
        // given
        given(currentSellerIdProvider.currentSellerId()).willReturn(1L);
        given(dropImageUploadService.issue("image/jpeg", 1048576L))
                .willThrow(new BusinessException(CommonErrorCode.EXTERNAL_SERVICE_ERROR));

        // when & then
        mockMvc.perform(post("/api/v1/uploads/images/presigned-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"shoes.jpg\",\"contentType\":\"image/jpeg\",\"fileSize\":1048576}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("EXTERNAL_SERVICE_ERROR"));
    }
}
