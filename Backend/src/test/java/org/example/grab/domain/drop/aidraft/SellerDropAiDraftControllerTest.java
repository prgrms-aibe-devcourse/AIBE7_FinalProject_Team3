package org.example.grab.domain.drop.aidraft;

import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellerDropAiDraftControllerTest {

    private static final String IMAGE = "{\"imageId\":\"7c0d3a1e-1b2f-4c55-9d1e-2a4b6c8d0e1f\",\"imageUrl\":\"https://x/a.jpg\"}";

    private DropAiDraftService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(DropAiDraftService.class);
        CurrentSellerIdProvider currentSellerIdProvider = mock(CurrentSellerIdProvider.class);
        given(currentSellerIdProvider.currentSellerId()).willReturn(3L);
        given(service.draft(anyLong(), any())).willReturn(new DropAiDraftResponse("가디건", null, 1L, null));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerDropAiDraftController(service, currentSellerIdProvider))
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("로그인한 판매자와 업로드 이미지로 초안을 요청하고 검증에 실패한 항목은 null로 돌려준다")
    void draft_passesSellerAndImages() throws Exception {
        mockMvc.perform(post("/api/v1/seller/drop-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"images\":[" + IMAGE + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("가디건"))
                .andExpect(jsonPath("$.data.description").doesNotExist());
        verify(service).draft(3L, new DropAiDraftRequest(List.of(new DropAiDraftRequest.Image(
                UUID.fromString("7c0d3a1e-1b2f-4c55-9d1e-2a4b6c8d0e1f"), "https://x/a.jpg"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"images\":[]}",
            "{\"images\":[" + IMAGE + "," + IMAGE + "," + IMAGE + "," + IMAGE + "]}",
            "{\"images\":[{\"imageUrl\":\"https://x/a.jpg\"}]}",
            "{\"images\":[{\"imageId\":\"7c0d3a1e-1b2f-4c55-9d1e-2a4b6c8d0e1f\",\"imageUrl\":\" \"}]}"
    })
    @DisplayName("이미지 누락·0개·4개 이상, imageId·imageUrl 누락은 VALIDATION_FAILED로 거부한다")
    void draft_rejectsInvalidRequest(String body) throws Exception {
        mockMvc.perform(post("/api/v1/seller/drop-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verify(service, never()).draft(anyLong(), any());
    }
}
