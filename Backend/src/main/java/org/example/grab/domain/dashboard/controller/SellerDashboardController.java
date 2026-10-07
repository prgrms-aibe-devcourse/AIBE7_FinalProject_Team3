package org.example.grab.domain.dashboard.controller;

import org.example.grab.domain.dashboard.dto.DropStatsResponse;
import org.example.grab.domain.dashboard.dto.SellerDashboardSummaryResponse;
import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropResponse;
import org.example.grab.domain.dashboard.service.SellerDashboardService;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/seller/dashboard")
@RequiredArgsConstructor
public class SellerDashboardController {

    private final SellerDashboardService sellerDashboardService;
    private final CurrentSellerIdProvider currentSellerIdProvider;

    @GetMapping("/upcoming-drops")
    public ApiResponse<List<UpcomingDropResponse>> findUpcomingDrops(
            @RequestParam(defaultValue = "START") UpcomingDropEventType eventType,
            @RequestParam(defaultValue = "60") int withinMinutes) {
        // RequsetParam으로 받은 값이 1시간 또는 1일이 아니면 오류 발생
        if (withinMinutes != 60 && withinMinutes != 1440) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.success(sellerDashboardService.findUpcomingDrops(
                currentSellerIdProvider.currentSellerId(), eventType, withinMinutes));
    }

    @GetMapping("/drops")
    public ApiResponse<PageResponse<DropStatsResponse>> findDropStats(
            @RequestParam(required = false) DropStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.success(sellerDashboardService.findDropStats(
                currentSellerIdProvider.currentSellerId(), status, page, size));
    }

    @GetMapping("/summary")
    public ApiResponse<SellerDashboardSummaryResponse> findSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.success(sellerDashboardService.findSummary(
                currentSellerIdProvider.currentSellerId(), from, to));
    }
}
