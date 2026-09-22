package org.example.grab.domain.order.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.OrderDetailResponse;
import org.example.grab.domain.order.dto.OrderStatusResponse;
import org.example.grab.domain.order.dto.OrderSummaryResponse;
import org.example.grab.domain.order.dto.ShipmentCreateRequest;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.service.SellerOrderService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.security.CurrentUserId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1/seller/orders")
@RequiredArgsConstructor
@Validated
public class SellerOrderController {

    private final SellerOrderService sellerOrderService;

    @GetMapping
    public ApiResponse<PageResponse<OrderSummaryResponse>> findOrders(
            @CurrentUserId Long sellerUserId,
            @RequestParam(required = false) Long dropId,
            @RequestParam(required = false) OrderStatus orderStatus,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(sellerOrderService.findOrders(
                sellerUserId, dropId, orderStatus, paymentStatus, page, size
        ));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderDetailResponse> findOrder(
            @CurrentUserId Long sellerUserId, @PathVariable Long orderId
    ) {
        return ApiResponse.success(sellerOrderService.findOrder(sellerUserId, orderId));
    }

    @PostMapping("/{orderId}/prepare-shipment")
    public ApiResponse<OrderStatusResponse> prepareShipment(
            @CurrentUserId Long sellerUserId, @PathVariable Long orderId
    ) {
        return ApiResponse.success(sellerOrderService.prepareShipment(sellerUserId, orderId));
    }

    @PostMapping("/{orderId}/shipment")
    public ApiResponse<OrderStatusResponse> ship(
            @CurrentUserId Long sellerUserId,
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ShipmentCreateRequest request
    ) {
        return ApiResponse.success(sellerOrderService.ship(
                sellerUserId, orderId, idempotencyKey, request
        ));
    }

    @PostMapping("/{orderId}/delivery-complete")
    public ApiResponse<OrderStatusResponse> completeDelivery(
            @CurrentUserId Long sellerUserId, @PathVariable Long orderId
    ) {
        return ApiResponse.success(sellerOrderService.completeDelivery(sellerUserId, orderId));
    }
}
