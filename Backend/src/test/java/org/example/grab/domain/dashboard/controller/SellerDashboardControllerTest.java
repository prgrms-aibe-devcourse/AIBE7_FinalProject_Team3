package org.example.grab.domain.dashboard.controller;

import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropResponse;
import org.example.grab.domain.dashboard.service.SellerDashboardService;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.security.CurrentSellerIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellerDashboardControllerTest {

    private RecordingDashboardService sellerDashboardService;
    private RecordingSellerIdProvider currentSellerIdProvider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        sellerDashboardService = new RecordingDashboardService();
        currentSellerIdProvider = new RecordingSellerIdProvider();
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
        sellerDashboardService.responses = List.of(new UpcomingDropResponse(
                10L, "상품", "WISH", UpcomingDropEventType.START,
                OffsetDateTime.parse("2026-10-01T01:00:00Z")));

        // when & then
        mockMvc.perform(get("/api/v1/seller/dashboard/upcoming-drops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].dropId").value(10))
                .andExpect(jsonPath("$.data[0].eventType").value("START"))
                .andExpect(jsonPath("$.data[0].status").value("WISH"));
        assertThat(sellerDashboardService.sellerId).isEqualTo(3L);
        assertThat(sellerDashboardService.eventType).isEqualTo(UpcomingDropEventType.START);
        assertThat(sellerDashboardService.withinMinutes).isEqualTo(60);
    }

    @Test
    @DisplayName("종료 임박과 1일을 선택하면 선택한 값으로 조회한다")
    void findUpcomingDrops_usesSelectedValues() throws Exception {
        // given
        sellerDashboardService.responses = List.of();

        // when & then
        mockMvc.perform(get("/api/v1/seller/dashboard/upcoming-drops")
                        .param("eventType", "END")
                        .param("withinMinutes", "1440"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        assertThat(sellerDashboardService.sellerId).isEqualTo(3L);
        assertThat(sellerDashboardService.eventType).isEqualTo(UpcomingDropEventType.END);
        assertThat(sellerDashboardService.withinMinutes).isEqualTo(1440);
    }

    @Test
    @DisplayName("허용하지 않는 기간은 400 INVALID_REQUEST로 거부한다")
    void findUpcomingDrops_rejectsUnsupportedDuration() throws Exception {
        mockMvc.perform(get("/api/v1/seller/dashboard/upcoming-drops")
                        .param("withinMinutes", "120"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(sellerDashboardService.called).isFalse();
        assertThat(currentSellerIdProvider.called).isFalse();
    }

    private static class RecordingDashboardService extends SellerDashboardService {

        private List<UpcomingDropResponse> responses = List.of();
        private boolean called;
        private long sellerId;
        private UpcomingDropEventType eventType;
        private int withinMinutes;

        private RecordingDashboardService() {
            super(null);
        }

        @Override
        public List<UpcomingDropResponse> findUpcomingDrops(
                long sellerId, UpcomingDropEventType eventType, int withinMinutes) {
            called = true;
            this.sellerId = sellerId;
            this.eventType = eventType;
            this.withinMinutes = withinMinutes;
            return responses;
        }
    }

    private static class RecordingSellerIdProvider implements CurrentSellerIdProvider {

        private boolean called;

        @Override
        public Long currentSellerId() {
            called = true;
            return 3L;
        }
    }
}
