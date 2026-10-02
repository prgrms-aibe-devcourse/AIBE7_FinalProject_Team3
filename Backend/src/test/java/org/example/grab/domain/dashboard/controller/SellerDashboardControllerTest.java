package org.example.grab.domain.dashboard.controller;

import org.example.grab.domain.dashboard.dto.SellerDashboardSummaryResponse;
import org.example.grab.domain.dashboard.dto.SellerDashboardSummaryResponse.StockSummary;
import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropResponse;
import org.example.grab.domain.dashboard.service.SellerDashboardService;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Mockito 기본 inline 방식은 테스트 JVM의 Byte Buddy 동적 attach 실패로 실행할 수 없어, test 리소스에서 subclass 방식을 사용한다.
class SellerDashboardControllerTest {

    private SellerDashboardService sellerDashboardService;
    private CurrentSellerIdProvider currentSellerIdProvider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        sellerDashboardService = mock(SellerDashboardService.class);
        currentSellerIdProvider = mock(CurrentSellerIdProvider.class);
        given(currentSellerIdProvider.currentSellerId()).willReturn(3L);
        SellerDashboardController controller = new SellerDashboardController(
                sellerDashboardService, currentSellerIdProvider);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
                .build();
    }

    @Test
    @DisplayName("기본 선택값은 시작 임박·1시간이며 DROP 목록을 반환한다")
    void findUpcomingDrops_usesDefaults() throws Exception {
        // given
        given(sellerDashboardService.findUpcomingDrops(
                3L, UpcomingDropEventType.START, 60)).willReturn(List.of(new UpcomingDropResponse(
                10L, "상품", "WISH", UpcomingDropEventType.START,
                OffsetDateTime.parse("2026-10-01T01:00:00Z"))));

        // when & then
        mockMvc.perform(get("/api/v1/seller/dashboard/upcoming-drops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].dropId").value(10))
                .andExpect(jsonPath("$.data[0].eventType").value("START"))
                .andExpect(jsonPath("$.data[0].status").value("WISH"));
        verify(sellerDashboardService).findUpcomingDrops(3L, UpcomingDropEventType.START, 60);
    }

    @Test
    @DisplayName("종료 임박과 1일을 선택하면 선택한 값으로 조회한다")
    void findUpcomingDrops_usesSelectedValues() throws Exception {
        // given
        given(sellerDashboardService.findUpcomingDrops(
                3L, UpcomingDropEventType.END, 1440)).willReturn(List.of());

        // when & then
        mockMvc.perform(get("/api/v1/seller/dashboard/upcoming-drops")
                        .param("eventType", "END")
                        .param("withinMinutes", "1440"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        verify(sellerDashboardService).findUpcomingDrops(3L, UpcomingDropEventType.END, 1440);
    }

    @Test
    @DisplayName("허용하지 않는 기간은 400 INVALID_REQUEST로 거부한다")
    void findUpcomingDrops_rejectsUnsupportedDuration() throws Exception {
        mockMvc.perform(get("/api/v1/seller/dashboard/upcoming-drops")
                        .param("withinMinutes", "120"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(sellerDashboardService, currentSellerIdProvider);
    }

    @Test
    @DisplayName("기간을 생략하면 제한 없이 요약을 조회한다")
    void findSummary_withoutPeriod() throws Exception {
        // given
        given(sellerDashboardService.findSummary(3L, null, null)).willReturn(new SellerDashboardSummaryResponse(
                Map.of("WISH", 2L), Map.of("PAID", 1L), Map.of("SUCCEEDED", 1L), 4L,
                new StockSummary(10L, 2L, 3L)));

        // when & then
        mockMvc.perform(get("/api/v1/seller/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dropCounts.WISH").value(2))
                .andExpect(jsonPath("$.data.reconciliationRequired").value(4))
                .andExpect(jsonPath("$.data.stockSummary.available").value(10));
        verify(sellerDashboardService).findSummary(3L, null, null);
    }

    @Test
    @DisplayName("from이 to보다 뒤면 400을 반환한다")
    void findSummary_rejectsReversedPeriod() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/seller/dashboard/summary")
                        .param("from", "2026-10-02T00:00:00+09:00")
                        .param("to", "2026-10-01T00:00:00+09:00"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sellerDashboardService);
    }

    @Test
    @DisplayName("오프셋 없는 기간은 400을 반환한다")
    void findSummary_rejectsDateTimeWithoutOffset() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/seller/dashboard/summary").param("from", "2026-10-01"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/seller/dashboard/summary").param("from", "2026-10-01T00:00:00"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sellerDashboardService);
    }
}
