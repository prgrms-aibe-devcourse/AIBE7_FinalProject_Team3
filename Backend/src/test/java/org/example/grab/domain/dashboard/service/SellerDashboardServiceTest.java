package org.example.grab.domain.dashboard.service;

import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropProjection;
import org.example.grab.domain.dashboard.dto.UpcomingDropResponse;
import org.example.grab.domain.dashboard.repository.SellerDashboardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SellerDashboardServiceTest {

    private SellerDashboardService sellerDashboardService;
    private RecordingRepository sellerDashboardRepository;

    @BeforeEach
    void setUp() {
        sellerDashboardRepository = new RecordingRepository();
        sellerDashboardService = new SellerDashboardService(sellerDashboardRepository);
    }

    @Test
    @DisplayName("시작 임박 조회는 60분 범위와 START 유형을 반환한다")
    void findUpcomingDrops_startWithinOneHour() {
        // given
        UpcomingDropProjection projection = new UpcomingDropProjection(
                10L, "시작 예정 상품", "WISH", OffsetDateTime.parse("2026-10-01T01:00:00Z"));
        sellerDashboardRepository.projections = List.of(projection);

        // when
        List<UpcomingDropResponse> result = sellerDashboardService.findUpcomingDrops(
                3L, UpcomingDropEventType.START, 60);

        // then
        assertThat(result).containsExactly(new UpcomingDropResponse(
                10L, "시작 예정 상품", "WISH", UpcomingDropEventType.START, projection.upcomingAt()));
        assertThat(sellerDashboardRepository.sellerId).isEqualTo(3L);
        assertThat(sellerDashboardRepository.eventType).isEqualTo(UpcomingDropEventType.START);
        assertThat(sellerDashboardRepository.deadline)
                .isEqualTo(sellerDashboardRepository.now.plusMinutes(60));
    }

    @Test
    @DisplayName("종료 임박 조회는 1440분 범위와 END 유형을 반환한다")
    void findUpcomingDrops_endWithinOneDay() {
        // given
        UpcomingDropProjection projection = new UpcomingDropProjection(
                20L, "종료 예정 상품", "GRAB", OffsetDateTime.parse("2026-10-02T00:00:00Z"));
        sellerDashboardRepository.projections = List.of(projection);

        // when
        List<UpcomingDropResponse> result = sellerDashboardService.findUpcomingDrops(
                3L, UpcomingDropEventType.END, 1440);

        // then
        assertThat(result).containsExactly(new UpcomingDropResponse(
                20L, "종료 예정 상품", "GRAB", UpcomingDropEventType.END, projection.upcomingAt()));
        assertThat(sellerDashboardRepository.sellerId).isEqualTo(3L);
        assertThat(sellerDashboardRepository.eventType).isEqualTo(UpcomingDropEventType.END);
        assertThat(sellerDashboardRepository.deadline)
                .isEqualTo(sellerDashboardRepository.now.plusMinutes(1440));
    }

    private static class RecordingRepository extends SellerDashboardRepository {

        private List<UpcomingDropProjection> projections = List.of();
        private Long sellerId;
        private UpcomingDropEventType eventType;
        private OffsetDateTime now;
        private OffsetDateTime deadline;

        private RecordingRepository() {
            super(null);
        }

        @Override
        public List<UpcomingDropProjection> findUpcomingDrops(
                long sellerId, UpcomingDropEventType eventType, OffsetDateTime now, OffsetDateTime deadline) {
            this.sellerId = sellerId;
            this.eventType = eventType;
            this.now = now;
            this.deadline = deadline;
            return projections;
        }
    }
}
