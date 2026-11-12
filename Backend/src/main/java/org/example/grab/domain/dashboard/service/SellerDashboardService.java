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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
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
    // dropId를 지정하면 본인 DROP 중 해당 1건만(다른 판매자 소유면 빈 목록) 반환한다.
    @Transactional(readOnly = true)
    public PageResponse<DropStatsResponse> findDropStats(
            long sellerId, DropStatus status, Long dropId, int page, int size) {
        return PageResponse.from(sellerDashboardRepository
                .findDropStats(sellerId, status == null ? null : status.name(), dropId, PageRequest.of(page, size))
                .map(DropStatsResponse::from));
    }

    @Transactional(readOnly = true)
    public SellerDashboardSummaryResponse findSummary(long sellerId, OffsetDateTime from, OffsetDateTime to) {
        long started = System.nanoTime();
        var metrics = sellerDashboardRepository.findSummaryMetrics(sellerId, from, to);
        long queryUs = (System.nanoTime() - started) / 1_000;
        Map<String, Long> drops = new HashMap<>(), orders = new HashMap<>(), payments = new HashMap<>();
        long reconciliation = 0, available = 0, reserved = 0, sold = 0;
        for (var metric : metrics) {
            switch (metric.metric()) {
                case "DROP" -> drops.put(metric.status(), metric.value());
                case "ORDER" -> orders.put(metric.status(), metric.value());
                case "PAYMENT" -> payments.put(metric.status(), metric.value());
                case "RECONCILIATION" -> reconciliation = metric.value();
                case "STOCK" -> {
                    switch (metric.status()) {
                        case "AVAILABLE" -> available = metric.value();
                        case "RESERVED" -> reserved = metric.value();
                        case "SOLD" -> sold = metric.value();
                        default -> throw new IllegalStateException("Unknown stock metric: " + metric.status());
                    }
                }
                default -> throw new IllegalStateException("Unknown dashboard metric: " + metric.metric());
            }
        }
        log.info("TEMP_DASHBOARD_SQL summary_query_us={}", queryUs);
        var stock = new SellerDashboardSummaryResponse.StockSummary(available, reserved, sold);
        return SellerDashboardSummaryResponse.of(drops, orders, payments, reconciliation, stock);
    }
}
