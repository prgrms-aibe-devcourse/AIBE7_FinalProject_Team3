package org.example.grab.domain.dashboard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "판매자 대시보드", description = "본인 DROP의 운영 현황·통계 조회. 403 ACCESS_DENIED는 SELLER 권한 없음")
public class SellerDashboardController {

    private final SellerDashboardService sellerDashboardService;
    private final CurrentSellerIdProvider currentSellerIdProvider;

    @Operation(summary = "임박 DROP 조회",
            description = "판매 시작(START, WISH 기준) 또는 종료(END, GRAB 기준)가 임박한 DROP을 임박 순으로 반환한다. withinMinutes는 60 또는 1440만 허용한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
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

    @Operation(summary = "DROP별 통계 조회",
            description = "DROP별 WISH·주문·판매·재고 통계를 최근 생성순으로 반환한다. 집계 기준은 SELLER.md 2.2 참고")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
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

    @Operation(summary = "대시보드 요약 조회",
            description = "DROP·주문·결제·재고 상태별 건수를 반환한다. from·to 기간은 주문·결제 건수에만 적용하며 from이 to보다 뒤면 400이다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED")
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
