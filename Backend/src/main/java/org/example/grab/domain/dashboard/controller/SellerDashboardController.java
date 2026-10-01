package org.example.grab.domain.dashboard.controller;

import org.example.grab.domain.dashboard.dto.UpcomingDropEventType;
import org.example.grab.domain.dashboard.dto.UpcomingDropResponse;
import org.example.grab.domain.dashboard.service.SellerDashboardService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.CurrentSellerIdProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
