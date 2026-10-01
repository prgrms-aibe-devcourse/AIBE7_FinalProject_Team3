package org.example.grab.domain.order.controller;

import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.example.grab.domain.order.dto.OrderStatusResponse;
import org.example.grab.domain.order.dto.SellerOrderDetailResponse;
import org.example.grab.domain.order.dto.SellerOrderListResponse;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.service.SellerOrderService;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.shipment.dto.ShipmentRegisterRequest;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.common.PublicIdParser;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 판매자 주문 조회 요청을 공통 응답으로 반환한다.
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/seller")
public class SellerOrderController {

    private final SellerOrderService sellerOrderService;
    private final CurrentSellerIdProvider currentSellerIdProvider;

    @GetMapping("/orders/{orderId}")
    public ApiResponse<SellerOrderDetailResponse> findOrder(@PathVariable String orderId) {
        UUID uuid = PublicIdParser.parse(orderId);
        return ApiResponse.success(
                sellerOrderService.findOrder(currentSellerIdProvider.currentSellerId(), uuid));
    }

    // Seller 인증 및 Drop 소유권 검증 후 order의 상태를 PAID -> PREPARING으로 전환
    @PostMapping("/orders/{orderId}/prepare-shipment")
    public ApiResponse<OrderStatusResponse> prepareShipment(@PathVariable String orderId) {
        return ApiResponse.success(sellerOrderService.prepareShipment(
                currentSellerIdProvider.currentSellerId(), PublicIdParser.parse(orderId)));
    }

    @PostMapping("/orders/{orderId}/shipment")
    public ApiResponse<OrderStatusResponse> registerShipment(
            @PathVariable String orderId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ShipmentRegisterRequest request) {
        return ApiResponse.success(sellerOrderService.registerShipment(
                currentSellerIdProvider.currentSellerId(), PublicIdParser.parse(orderId), idempotencyKey, request));
    }

    @GetMapping("/orders")
    public ApiResponse<PageResponse<SellerOrderListResponse>> listOrders(
            @RequestParam(required = false) Long dropId,
            @RequestParam(required = false) OrderStatus orderStatus,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100 || (dropId != null && dropId < 1)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.success(sellerOrderService.findOrders(
                currentSellerIdProvider.currentSellerId(), dropId, orderStatus, paymentStatus, page, size));
    }

}
