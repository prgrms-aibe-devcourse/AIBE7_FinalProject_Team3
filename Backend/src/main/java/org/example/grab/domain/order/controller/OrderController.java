package org.example.grab.domain.order.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.dto.OrderCancelRequest;
import org.example.grab.domain.order.dto.OrderStatusResponse;
import org.example.grab.domain.order.dto.OrderCreateResponse;
import org.example.grab.domain.order.dto.OrderDetailResponse;
import org.example.grab.domain.order.dto.OrderSummaryResponse;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.service.OrderCreationService;
import org.example.grab.domain.order.service.OrderCancellationService;
import org.example.grab.domain.order.service.OrderQueryService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.security.CurrentUserId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final OrderCreationService orderCreationService;
    private final OrderQueryService orderQueryService;
    private final OrderCancellationService orderCancellationService;

    @PostMapping
    public ResponseEntity<ApiResponse<OrderCreateResponse>> createOrder(
            @CurrentUserId Long buyerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OrderCreateRequest request
    ) {
        OrderCreateResponse response = orderCreationService.create(buyerId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<PageResponse<OrderSummaryResponse>> findMyOrders(
            @CurrentUserId Long buyerId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(orderQueryService.findMyOrders(buyerId, status, page, size));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderDetailResponse> findMyOrder(
            @CurrentUserId Long buyerId,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(orderQueryService.findMyOrder(buyerId, orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<OrderStatusResponse> cancelOrder(
            @CurrentUserId Long buyerId,
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OrderCancelRequest request
    ) {
        return ApiResponse.success(
                orderCancellationService.cancel(buyerId, orderId, idempotencyKey, request)
        );
    }
}
