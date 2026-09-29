package org.example.grab.domain.order.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.MyOrderDetailResponse;
import org.example.grab.domain.order.dto.MyOrderListResponse;
import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.dto.OrderCreateResponse;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.service.OrderCreateService;
import org.example.grab.domain.order.service.OrderQueryService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.common.PublicIdParser;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.CurrentUserIdProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderCreateService orderCreateService;
    private final OrderQueryService orderQueryService;
    private final CurrentUserIdProvider currentUserIdProvider;

    @PostMapping
    public ResponseEntity<ApiResponse<OrderCreateResponse>> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderCreateRequest request
    ) {
        OrderCreateResponse response = orderCreateService.create(
                currentUserIdProvider.currentUserId(),
                idempotencyKey,
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<PageResponse<MyOrderListResponse>> findMyOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.success(orderQueryService.findMyOrders(
                currentUserIdProvider.currentUserId(), status, page, size));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<MyOrderDetailResponse> findMyOrder(@PathVariable String orderId) {
        return ApiResponse.success(orderQueryService.findMyOrder(
                currentUserIdProvider.currentUserId(), PublicIdParser.parse(orderId)));
    }
}
