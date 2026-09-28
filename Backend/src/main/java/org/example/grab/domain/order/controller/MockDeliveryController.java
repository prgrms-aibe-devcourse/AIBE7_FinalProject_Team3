package org.example.grab.domain.order.controller;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.OrderStatusResponse;
import org.example.grab.domain.order.service.MockDeliveryService;
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
public class MockDeliveryController {

    private final MockDeliveryService mockDeliveryService;

    @PostMapping("/{orderId}/delivery/complete")
    public ApiResponse<OrderStatusResponse> completeDelivery(@PathVariable UUID orderId) {
        return ApiResponse.success(mockDeliveryService.completeDelivery(orderId));
    }
}
