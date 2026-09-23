package org.example.grab.domain.order.controller;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.SellerOrderListResponse;
import org.example.grab.domain.order.service.SellerOrderService;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.PaymentStatus;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 판매자 주문 목록 조회 요청을 받고 공통 응답으로 반환한다.
@RequiredArgsConstructor
@RestController
@Validated
@RequestMapping("/api/v1/seller")
public class SellerOrderController {

    private final SellerOrderService sellerOrderService;

    @GetMapping("/orders")
    public ApiResponse<PageResponse<SellerOrderListResponse>> listOrders(
            Authentication authentication,
            @RequestParam(required = false) Long dropId,
            @RequestParam(required = false) OrderStatus orderStatus,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100 || (dropId != null && dropId < 1)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.success(sellerOrderService.findOrders(
                authentication.getName(), dropId, orderStatus, paymentStatus, page, size));
    }

}
