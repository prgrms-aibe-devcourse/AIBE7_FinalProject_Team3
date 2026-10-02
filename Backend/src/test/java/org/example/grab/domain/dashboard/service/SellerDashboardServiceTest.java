package org.example.grab.domain.dashboard.service;

import org.example.grab.domain.dashboard.dto.SellerDashboardSummaryResponse;
import org.example.grab.domain.dashboard.dto.SellerDashboardSummaryResponse.StockSummary;
import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropProjection;
import org.example.grab.domain.dashboard.dto.UpcomingDropResponse;
import org.example.grab.domain.dashboard.repository.SellerDashboardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SellerDashboardServiceTest {

    private SellerDashboardService sellerDashboardService;
    private SellerDashboardRepository sellerDashboardRepository;

    @BeforeEach
    void setUp() {
        sellerDashboardRepository = mock(SellerDashboardRepository.class);
        sellerDashboardService = new SellerDashboardService(sellerDashboardRepository);
    }

    @Test
    @DisplayName("시작 임박 조회는 60분 범위와 START 유형을 반환한다")
    void findUpcomingDrops_startWithinOneHour() {
        // given
        UpcomingDropProjection projection = new UpcomingDropProjection(
                10L, "시작 예정 상품", "WISH", OffsetDateTime.parse("2026-10-01T01:00:00Z"));
        given(sellerDashboardRepository.findUpcomingDrops(
                eq(3L), eq(UpcomingDropEventType.START), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .willReturn(List.of(projection));

        // when
        List<UpcomingDropResponse> result = sellerDashboardService.findUpcomingDrops(
                3L, UpcomingDropEventType.START, 60);

        // then
        assertThat(result).containsExactly(new UpcomingDropResponse(
                10L, "시작 예정 상품", "WISH", UpcomingDropEventType.START, projection.upcomingAt()));
        var now = forClass(OffsetDateTime.class);
        var deadline = forClass(OffsetDateTime.class);
        verify(sellerDashboardRepository).findUpcomingDrops(
                eq(3L), eq(UpcomingDropEventType.START), now.capture(), deadline.capture());
        assertThat(deadline.getValue()).isEqualTo(now.getValue().plusMinutes(60));
    }

    @Test
    @DisplayName("종료 임박 조회는 1440분 범위와 END 유형을 반환한다")
    void findUpcomingDrops_endWithinOneDay() {
        // given
        UpcomingDropProjection projection = new UpcomingDropProjection(
                20L, "종료 예정 상품", "GRAB", OffsetDateTime.parse("2026-10-02T00:00:00Z"));
        given(sellerDashboardRepository.findUpcomingDrops(
                eq(3L), eq(UpcomingDropEventType.END), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .willReturn(List.of(projection));

        // when
        List<UpcomingDropResponse> result = sellerDashboardService.findUpcomingDrops(
                3L, UpcomingDropEventType.END, 1440);

        // then
        assertThat(result).containsExactly(new UpcomingDropResponse(
                20L, "종료 예정 상품", "GRAB", UpcomingDropEventType.END, projection.upcomingAt()));
        var now = forClass(OffsetDateTime.class);
        var deadline = forClass(OffsetDateTime.class);
        verify(sellerDashboardRepository).findUpcomingDrops(
                eq(3L), eq(UpcomingDropEventType.END), now.capture(), deadline.capture());
        assertThat(deadline.getValue()).isEqualTo(now.getValue().plusMinutes(1440));
    }

    @Test
    @DisplayName("요약은 건수가 없는 상태도 0으로 채워 반환한다")
    void findSummary_fillsMissingStatusWithZero() {
        // given
        given(sellerDashboardRepository.countDropsByStatus(3L)).willReturn(Map.of("WISH", 2L));
        given(sellerDashboardRepository.countOrdersByStatus(3L, null, null)).willReturn(Map.of("PAID", 1L));
        given(sellerDashboardRepository.countPaymentsByStatus(3L, null, null)).willReturn(Map.of());
        given(sellerDashboardRepository.countReconciliationRequired(3L)).willReturn(1L);
        given(sellerDashboardRepository.sumStock(3L)).willReturn(new StockSummary(10L, 2L, 3L));

        // when
        SellerDashboardSummaryResponse response = sellerDashboardService.findSummary(3L, null, null);

        // then
        assertThat(response.dropCounts()).containsOnlyKeys("DRAFT", "WISH", "GRAB", "ENDED", "CANCELED");
        assertThat(response.dropCounts()).containsEntry("WISH", 2L).containsEntry("DRAFT", 0L);
        assertThat(response.orderCounts()).containsOnlyKeys(
                "PAYMENT_PENDING", "PAID", "PREPARING", "SHIPPED", "DELIVERED", "EXPIRED", "CANCELED");
        assertThat(response.orderCounts()).containsEntry("PAID", 1L).containsEntry("DELIVERED", 0L);
        assertThat(response.paymentCounts()).containsOnlyKeys(
                "PENDING", "SUCCEEDED", "FAILED", "UNKNOWN", "CANCELED");
        assertThat(response.paymentCounts().values()).containsOnly(0L);
        assertThat(response.reconciliationRequired()).isEqualTo(1L);
        assertThat(response.stockSummary()).isEqualTo(new StockSummary(10L, 2L, 3L));
    }
}
