package org.example.grab.domain.demand.controller;

import org.example.grab.domain.demand.dto.StockRecommendationRequest;
import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionInput;
import org.example.grab.domain.demand.service.StockRecommendationService;
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

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellerStockRecommendationControllerTest {

    private StockRecommendationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(StockRecommendationService.class);
        CurrentSellerIdProvider currentSellerIdProvider = mock(CurrentSellerIdProvider.class);
        given(currentSellerIdProvider.currentSellerId()).willReturn(3L);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerStockRecommendationController(service, currentSellerIdProvider))
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("폼 입력값과 로그인한 판매자로 추천을 계산한다")
    void recommend_passesFormInput() throws Exception {
        mockMvc.perform(post("/api/v1/seller/stock-recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":7,\"options\":[{\"unitPrice\":59000,\"quantity\":40},"
                                + "{\"unitPrice\":59000}],\"safetyStockRate\":0.05}"))
                .andExpect(status().isOk());
        verify(service).recommend(3L, new StockRecommendationRequest(7L, List.of(
                new OptionInput(59000L, 40), new OptionInput(59000L, null)), new BigDecimal("0.05")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"options\":[{\"unitPrice\":1000}]}",
            "{\"categoryId\":7,\"options\":[]}",
            "{\"categoryId\":7,\"options\":[{\"unitPrice\":-1}]}",
            "{\"categoryId\":7,\"options\":[{\"quantity\":1}]}",
            "{\"categoryId\":7,\"options\":[{\"unitPrice\":1000,\"quantity\":-1}]}",
            "{\"categoryId\":7,\"options\":[{\"unitPrice\":1000}],\"safetyStockRate\":1.5}",
            "{\"categoryId\":7,\"options\":[{\"unitPrice\":1000}],\"safetyStockRate\":0.125}"
    })
    @DisplayName("카테고리·옵션 누락, 음수 가격·수량, 범위·자릿수를 벗어난 안전 재고율은 VALIDATION_FAILED로 거부한다")
    void recommend_rejectsInvalidInput(String body) throws Exception {
        mockMvc.perform(post("/api/v1/seller/stock-recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verify(service, never()).recommend(anyLong(), any());
    }
}
