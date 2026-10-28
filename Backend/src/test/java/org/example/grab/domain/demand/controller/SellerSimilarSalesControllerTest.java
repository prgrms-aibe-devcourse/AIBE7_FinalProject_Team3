package org.example.grab.domain.demand.controller;

import org.example.grab.domain.demand.dto.SimilarSalesRequest;
import org.example.grab.domain.demand.dto.SimilarSalesRequest.OptionGroupInput;
import org.example.grab.domain.demand.service.SimilarSalesService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellerSimilarSalesControllerTest {

    private SimilarSalesService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(SimilarSalesService.class);
        CurrentSellerIdProvider currentSellerIdProvider = mock(CurrentSellerIdProvider.class);
        given(currentSellerIdProvider.currentSellerId()).willReturn(3L);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerSimilarSalesController(service, currentSellerIdProvider))
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("생성 단계는 폼 입력값과 로그인한 판매자로 계산한다")
    void forForm_passesFormInput() throws Exception {
        mockMvc.perform(post("/api/v1/seller/similar-sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":7,\"name\":\"키링\",\"description\":\"아크릴\","
                                + "\"optionGroups\":[{\"name\":\"색상\",\"values\":[\"블랙\"]}],"
                                + "\"unitPrices\":[12000,15000]}"))
                .andExpect(status().isOk());
        verify(service).forForm(3L, new SimilarSalesRequest(7L, "키링", "아크릴",
                List.of(new OptionGroupInput("색상", List.of("블랙"))), List.of(12000L, 15000L)));
    }

    @Test
    @DisplayName("WISH 단계는 경로의 DROP ID와 로그인한 판매자로 계산한다")
    void forWishDrop_passesDropId() throws Exception {
        mockMvc.perform(get("/api/v1/seller/drops/42/similar-sales"))
                .andExpect(status().isOk());
        verify(service).forWishDrop(3L, 42L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"unitPrices\":[1000]}",
            "{\"categoryId\":7}",
            "{\"categoryId\":7,\"unitPrices\":[]}",
            "{\"categoryId\":7,\"unitPrices\":[-1]}",
            "{\"categoryId\":7,\"unitPrices\":[1000],\"optionGroups\":[{\"name\":\"색상\"}]}"
    })
    @DisplayName("카테고리·가격 누락, 빈 가격 목록, 음수 가격, 값 없는 옵션 그룹은 VALIDATION_FAILED로 거부한다")
    void forForm_rejectsInvalidInput(String body) throws Exception {
        mockMvc.perform(post("/api/v1/seller/similar-sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verify(service, never()).forForm(anyLong(), any());
    }
}
