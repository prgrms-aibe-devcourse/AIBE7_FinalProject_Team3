package org.example.grab.domain.demand.controller;

import org.example.grab.domain.demand.dto.DemandCalculateRequest;
import org.example.grab.domain.demand.dto.DemandPlanDecisionRequest;
import org.example.grab.domain.demand.dto.DemandPlanDecisionRequest.DecidedQuantity;
import org.example.grab.domain.demand.dto.DemandPlanResponse;
import org.example.grab.domain.demand.entity.DemandPlanStatus;
import org.example.grab.domain.demand.service.DemandPlanService;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellerDemandPlanControllerTest {

    private static final UUID PLAN_ID = UUID.fromString("4f0c9a52-1d6b-4f4e-9a57-8f6d2c1e0b11");

    private DemandPlanService demandPlanService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        demandPlanService = mock(DemandPlanService.class);
        CurrentSellerIdProvider currentSellerIdProvider = mock(CurrentSellerIdProvider.class);
        CurrentUserIdProvider currentUserIdProvider = mock(CurrentUserIdProvider.class);
        given(currentSellerIdProvider.currentSellerId()).willReturn(3L);
        given(currentUserIdProvider.currentUserId()).willReturn(30L);
        DemandPlanResponse response = new DemandPlanResponse(PLAN_ID, 100L, DemandPlanStatus.DRAFT, null, null,
                null, null, null);
        given(demandPlanService.save(anyLong(), anyLong(), anyLong(), any())).willReturn(response);
        given(demandPlanService.decide(anyLong(), any(), any())).willReturn(response);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerDemandPlanController(
                        demandPlanService, currentSellerIdProvider, currentUserIdProvider))
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("계획 저장은 201과 계획 상세를 반환하고 저장한 회원을 함께 넘긴다")
    void save_returnsCreated() throws Exception {
        mockMvc.perform(post("/api/v1/seller/drops/100/demand-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.planId").value(PLAN_ID.toString()));
        verify(demandPlanService).save(3L, 30L, 100L, DemandCalculateRequest.defaults());
    }

    @Test
    @DisplayName("MODIFIED 결정은 수량과 함께 서비스로 넘긴다")
    void decide_passesModifiedQuantities() throws Exception {
        mockMvc.perform(post("/api/v1/seller/demand-plans/" + PLAN_ID + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"MODIFIED\",\"quantities\":[{\"optionId\":1001,\"quantity\":0}],\"reason\":\"조정\"}"))
                .andExpect(status().isOk());
        verify(demandPlanService).decide(3L, PLAN_ID, new DemandPlanDecisionRequest(
                DemandPlanStatus.MODIFIED, List.of(new DecidedQuantity(1001L, 0)), "조정"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"status\":\"DRAFT\"}",
            "{\"status\":\"MODIFIED\"}",
            "{\"status\":\"MODIFIED\",\"quantities\":[]}",
            "{\"status\":\"ACCEPTED\",\"quantities\":[{\"optionId\":1,\"quantity\":1}]}",
            "{\"status\":\"MODIFIED\",\"quantities\":[{\"optionId\":1,\"quantity\":-1}]}",
            "{\"status\":\"MODIFIED\",\"quantities\":[{\"quantity\":1}]}"
    })
    @DisplayName("결정 상태 누락·DRAFT, MODIFIED가 아닌 수량, 빠진 수량, 음수 수량은 VALIDATION_FAILED로 거부한다")
    void decide_rejectsInvalidRequest(String body) throws Exception {
        mockMvc.perform(post("/api/v1/seller/demand-plans/" + PLAN_ID + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verify(demandPlanService, never()).decide(anyLong(), any(), any());
    }

    @Test
    @DisplayName("결정 사유는 500자까지 받는다")
    void decide_rejectsLongReason() throws Exception {
        mockMvc.perform(post("/api/v1/seller/demand-plans/" + PLAN_ID + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\",\"reason\":\"" + "가".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("형식이 잘못된 planId는 RESOURCE_NOT_FOUND로 응답한다")
    void findPlan_rejectsMalformedPlanId() throws Exception {
        mockMvc.perform(get("/api/v1/seller/demand-plans/not-a-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("계획 목록의 페이지 크기는 1~100만 허용한다")
    void findPlans_rejectsInvalidPage() throws Exception {
        mockMvc.perform(get("/api/v1/seller/drops/100/demand-plans").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
