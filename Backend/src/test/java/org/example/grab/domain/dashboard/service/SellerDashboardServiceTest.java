package org.example.grab.domain.dashboard.service;

import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropProjection;
import org.example.grab.domain.dashboard.dto.UpcomingDropResponse;
import org.example.grab.domain.dashboard.repository.SellerDashboardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SellerDashboardServiceTest {

    @Mock
    private SellerDashboardRepository sellerDashboardRepository;

    @InjectMocks
    private SellerDashboardService sellerDashboardService;

    @Test
    @DisplayName("시작 임박 조회는 60분 범위와 START 유형을 반환한다")
    void findUpcomingDrops_startWithinOneHour() {
        // given
        UpcomingDropProjection projection = new UpcomingDropProjection(
                10L, "시작 예정 상품", "WISH", OffsetDateTime.parse("2026-10-01T01:00:00Z"));
        given(sellerDashboardRepository.findUpcomingDrops(
                eq(3L), eq(UpcomingDropEventType.START), any(), any()))
                .willReturn(List.of(projection));

        // when
        List<UpcomingDropResponse> result = sellerDashboardService.findUpcomingDrops(
                3L, UpcomingDropEventType.START, 60);

        // then
        assertThat(result).containsExactly(new UpcomingDropResponse(
                10L, "시작 예정 상품", "WISH", UpcomingDropEventType.START, projection.upcomingAt()));
        ArgumentCaptor<OffsetDateTime> nowCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> deadlineCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(sellerDashboardRepository).findUpcomingDrops(
                eq(3L), eq(UpcomingDropEventType.START), nowCaptor.capture(), deadlineCaptor.capture());
        assertThat(deadlineCaptor.getValue()).isEqualTo(nowCaptor.getValue().plusMinutes(60));
    }

    @Test
    @DisplayName("종료 임박 조회는 1440분 범위와 END 유형을 반환한다")
    void findUpcomingDrops_endWithinOneDay() {
        // given
        UpcomingDropProjection projection = new UpcomingDropProjection(
                20L, "종료 예정 상품", "GRAB", OffsetDateTime.parse("2026-10-02T00:00:00Z"));
        given(sellerDashboardRepository.findUpcomingDrops(
                eq(3L), eq(UpcomingDropEventType.END), any(), any()))
                .willReturn(List.of(projection));

        // when
        List<UpcomingDropResponse> result = sellerDashboardService.findUpcomingDrops(
                3L, UpcomingDropEventType.END, 1440);

        // then
        assertThat(result).containsExactly(new UpcomingDropResponse(
                20L, "종료 예정 상품", "GRAB", UpcomingDropEventType.END, projection.upcomingAt()));
        ArgumentCaptor<OffsetDateTime> nowCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> deadlineCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(sellerDashboardRepository).findUpcomingDrops(
                eq(3L), eq(UpcomingDropEventType.END), nowCaptor.capture(), deadlineCaptor.capture());
        assertThat(deadlineCaptor.getValue()).isEqualTo(nowCaptor.getValue().plusMinutes(1440));
    }
}
