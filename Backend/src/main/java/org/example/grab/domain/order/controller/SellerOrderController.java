package org.example.grab.domain.order.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.example.grab.domain.order.dto.OrderStatusResponse;
import org.example.grab.domain.order.dto.SellerOrderDetailResponse;
import org.example.grab.domain.order.dto.SellerOrderListResponse;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.service.SellerOrderService;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.shipment.dto.ShipmentRegisterRequest;
import org.example.grab.domain.shipment.dto.ShipmentUpdateResponse;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.common.PublicIdParser;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@Tag(name = "판매자 주문·배송", description = "본인 DROP 주문 조회와 배송 준비·발송·송장 수정. 403 ACCESS_DENIED는 SELLER 권한 없음 또는 CSRF 토큰 누락")
public class SellerOrderController {

    private final SellerOrderService sellerOrderService;
    private final CurrentSellerIdProvider currentSellerIdProvider;

    @Operation(summary = "판매자 주문 상세 조회", description = "본인 DROP의 주문만 조회할 수 있다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, ORDER_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND (UUID 형식 오류), ORDER_NOT_FOUND")
    @GetMapping("/orders/{orderId}")
    public ApiResponse<SellerOrderDetailResponse> findOrder(@PathVariable String orderId) {
        UUID uuid = PublicIdParser.parse(orderId);
        return ApiResponse.success(
                sellerOrderService.findOrder(currentSellerIdProvider.currentSellerId(), uuid));
    }

    // Seller 인증 및 Drop 소유권 검증 후 order의 상태를 PAID -> PREPARING으로 전환
    @Operation(summary = "배송 준비 처리", description = "PAID 주문을 PREPARING으로 바꾼다. 소비자 취소로 결제 취소를 진행·확인 중이면 거부한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "전환 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, ORDER_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND (UUID 형식 오류), ORDER_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION, PAYMENT_CANCELLATION_UNKNOWN")
    @PostMapping("/orders/{orderId}/prepare-shipment")
    public ApiResponse<OrderStatusResponse> prepareShipment(@PathVariable String orderId) {
        return ApiResponse.success(sellerOrderService.prepareShipment(
                currentSellerIdProvider.currentSellerId(), PublicIdParser.parse(orderId)));
    }

    @Operation(summary = "배송 정보 등록 및 발송 처리",
            description = "택배사·송장번호를 등록하고 PREPARING 주문을 SHIPPED로 바꾼다. Idempotency-Key(UUID) 헤더가 필요하다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발송 처리 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, INVALID_IDEMPOTENCY_KEY, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, ORDER_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND (UUID 형식 오류), ORDER_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_IDEMPOTENCY_KEY, ORDER_STATUS_CONFLICT, PAYMENT_CANCELLATION_UNKNOWN")
    @PostMapping("/orders/{orderId}/shipment")
    public ApiResponse<OrderStatusResponse> registerShipment(
            @PathVariable String orderId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ShipmentRegisterRequest request) {
        return ApiResponse.success(sellerOrderService.registerShipment(
                currentSellerIdProvider.currentSellerId(), PublicIdParser.parse(orderId), idempotencyKey, request));
    }

    // 상태 전이 없이 송장 정보만 갱신하므로 Idempotency-Key를 받지 않는다.
    @Operation(summary = "송장 정보 수정", description = "SHIPPED 주문의 택배사·송장번호만 고친다. 상태는 바뀌지 않는다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED, ORDER_ACCESS_DENIED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND (UUID 형식 오류), ORDER_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ORDER_STATUS_CONFLICT")
    @PatchMapping("/orders/{orderId}/shipment")
    public ApiResponse<ShipmentUpdateResponse> updateShipment(
            @PathVariable String orderId,
            @Valid @RequestBody ShipmentRegisterRequest request) {
        return ApiResponse.success(sellerOrderService.updateShipment(
                currentSellerIdProvider.currentSellerId(), PublicIdParser.parse(orderId), request));
    }

    @Operation(summary = "판매자 주문 목록 조회",
            description = "본인 DROP의 주문만 반환한다. dropId·orderStatus·paymentStatus를 함께 주면 모두 만족하는 주문만 반환한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED (다른 판매자의 dropId 포함)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND (없는 dropId)")
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
