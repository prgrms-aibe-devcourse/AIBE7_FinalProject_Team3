package org.example.grab.domain.demand.controller;

import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse;
import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.domain.demand.explanation.DemandExplanationResponse;
import org.example.grab.domain.demand.explanation.DemandExplanationService;
import org.example.grab.domain.demand.explanation.ExplanationStatus;
import org.example.grab.domain.demand.service.DemandAnalysisService;
import org.example.grab.global.error.BusinessException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellerDemandControllerTest {

    private DemandAnalysisService demandAnalysisService;
    private DemandExplanationService demandExplanationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        demandAnalysisService = mock(DemandAnalysisService.class);
        demandExplanationService = mock(DemandExplanationService.class);
        CurrentSellerIdProvider currentSellerIdProvider = mock(CurrentSellerIdProvider.class);
        given(currentSellerIdProvider.currentSellerId()).willReturn(3L);
        given(demandAnalysisService.analyze(anyLong(), anyLong(), any())).willReturn(response());
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerDemandController(
                        demandAnalysisService, demandExplanationService, currentSellerIdProvider))
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("수요 분석 조회는 기본 가정으로 계산한다")
    void analyze_usesDefaultAssumptions() throws Exception {
        mockMvc.perform(get("/api/v1/seller/drops/100/demand-analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dropId").value(100));
        verify(demandAnalysisService).analyze(3L, 100L, DemandCalculateRequest.defaults());
    }

    @Test
    @DisplayName("재계산은 요청한 기준과 안전 재고율을 서비스에 넘긴다")
    void calculate_passesRequest() throws Exception {
        mockMvc.perform(post("/api/v1/seller/drops/100/demand-analysis/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversionBasis\":\"OWN\",\"safetyStockRate\":0.05}"))
                .andExpect(status().isOk());
        verify(demandAnalysisService).analyze(3L, 100L,
                new DemandCalculateRequest(ConversionBasis.OWN, null, new BigDecimal("0.05")));
    }

    @Test
    @DisplayName("설명 요청은 판매자가 고른 가정을 넘기고, 본문이 없으면 기본 가정으로 설명한다")
    void explain_passesAssumptions() throws Exception {
        given(demandExplanationService.explain(anyLong(), anyLong(), any()))
                .willReturn(DemandExplanationResponse.of(ExplanationStatus.FAILED));

        mockMvc.perform(post("/api/v1/seller/drops/100/demand-analysis/explanation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversionBasis\":\"OWN\",\"safetyStockRate\":0.05}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        verify(demandExplanationService).explain(3L, 100L,
                new DemandCalculateRequest(ConversionBasis.OWN, null, new BigDecimal("0.05")));

        mockMvc.perform(post("/api/v1/seller/drops/100/demand-analysis/explanation"))
                .andExpect(status().isOk());
        verify(demandExplanationService).explain(3L, 100L, DemandCalculateRequest.defaults());
    }

    @Test
    @DisplayName("설명 하루 한도를 넘으면 429 DEMAND_EXPLANATION_LIMIT_EXCEEDED로 응답한다")
    void explain_rejectsOverLimit() throws Exception {
        given(demandExplanationService.explain(anyLong(), anyLong(), any()))
                .willThrow(new BusinessException(DemandErrorCode.DEMAND_EXPLANATION_LIMIT_EXCEEDED));

        mockMvc.perform(post("/api/v1/seller/drops/100/demand-analysis/explanation"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("DEMAND_EXPLANATION_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("기준 지정과 전환율 직접 입력을 함께 보내면 VALIDATION_FAILED로 거부한다")
    void calculate_rejectsBasisWithRate() throws Exception {
        mockMvc.perform(post("/api/v1/seller/drops/100/demand-analysis/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversionBasis\":\"MARKET\",\"conversionRate\":0.3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verify(demandAnalysisService, never()).analyze(anyLong(), anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.1", "1.01", "0.255"})
    @DisplayName("전환율은 0 초과 1 이하, 소수 둘째 자리까지만 허용한다")
    void calculate_rejectsInvalidConversionRate(String rate) throws Exception {
        mockMvc.perform(post("/api/v1/seller/drops/100/demand-analysis/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversionRate\":" + rate + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.5", "0.123"})
    @DisplayName("안전 재고율은 0 이상 1 이하, 소수 둘째 자리까지만 허용한다")
    void calculate_rejectsInvalidSafetyStockRate(String rate) throws Exception {
        mockMvc.perform(post("/api/v1/seller/drops/100/demand-analysis/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"safetyStockRate\":" + rate + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private static DemandAnalysisResponse response() {
        return new DemandAnalysisResponse(100L, null, "v1", null, null, null, null, null, null, List.of(), List.of());
    }
}
