package org.example.grab.domain.shipment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.OrderStatusResponse;
import org.example.grab.domain.shipment.service.MockDeliveryService;
import org.example.grab.global.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/mock/orders")
@ConditionalOnProperty(prefix = "grab.mock-delivery", name = "enabled", havingValue = "true")
@Tag(name = "Mock 배송", description = "로컬·테스트 전용. GRAB_MOCK_DELIVERY_ENABLED=true일 때만 노출된다")
public class MockDeliveryController {

    private final MockDeliveryService mockDeliveryService;

    @Operation(summary = "Mock 배송 완료 반영",
            description = "SHIPPED 주문을 DELIVERED로 바꾼다. 이미 DELIVERED면 현재 결과를 반환한다. 로그인이 필요하다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "배송 완료 반영")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST (UUID 형식 오류)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED (CSRF 토큰 누락)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATE_TRANSITION")
    @PostMapping("/{orderId}/delivery/complete")
    public ApiResponse<OrderStatusResponse> completeDelivery(@PathVariable UUID orderId) {
        return ApiResponse.success(mockDeliveryService.completeDelivery(orderId));
    }
}
