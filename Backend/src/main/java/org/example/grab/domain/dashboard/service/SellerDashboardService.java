package org.example.grab.domain.dashboard.service;

import org.example.grab.domain.dashboard.dto.DropStatsResponse;
import org.example.grab.domain.dashboard.dto.SellerDashboardSummaryResponse;
import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropProjection;
import org.example.grab.domain.dashboard.dto.UpcomingDropResponse;
import org.example.grab.domain.dashboard.repository.SellerDashboardRepository;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.global.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SellerDashboardService {

    private final SellerDashboardRepository sellerDashboardRepository;

    @Transactional(readOnly = true)
    public List<UpcomingDropResponse> findUpcomingDrops(
            long sellerId, UpcomingDropEventType eventType, int withinMinutes) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime deadline = now.plusMinutes(withinMinutes);
        List<UpcomingDropProjection> drops = sellerDashboardRepository.findUpcomingDrops(
                sellerId, eventType, now, deadline);
        return drops.stream().map(drop -> UpcomingDropResponse.from(drop, eventType)).toList();
    }

    // status는 enum으로 받아 Repository에는 이름만 넘긴다. 생략하면 전체 DROP을 센다.
    @Transactional(readOnly = true)
    public PageResponse<DropStatsResponse> findDropStats(long sellerId, DropStatus status, int page, int size) {
        return PageResponse.from(sellerDashboardRepository
                .findDropStats(sellerId, status == null ? null : status.name(), PageRequest.of(page, size))
                .map(DropStatsResponse::from));
    }

    @Transactional(readOnly = true)
    public SellerDashboardSummaryResponse findSummary(long sellerId, OffsetDateTime from, OffsetDateTime to) {
        return SellerDashboardSummaryResponse.of(
                sellerDashboardRepository.countDropsByStatus(sellerId),
                sellerDashboardRepository.countOrdersByStatus(sellerId, from, to),
                sellerDashboardRepository.countPaymentsByStatus(sellerId, from, to),
                sellerDashboardRepository.countReconciliationRequired(sellerId),
                sellerDashboardRepository.sumStock(sellerId));
    }
}
