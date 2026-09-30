package org.example.grab.domain.dashboard.service;

import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropProjection;
import org.example.grab.domain.dashboard.dto.UpcomingDropResponse;
import org.example.grab.domain.dashboard.repository.SellerDashboardRepository;
import lombok.RequiredArgsConstructor;
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
}
