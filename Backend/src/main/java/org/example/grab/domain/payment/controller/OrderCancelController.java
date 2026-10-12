package org.example.grab.domain.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.payment.dto.OrderCancelRequest;
import org.example.grab.domain.payment.dto.OrderCancelResponse;
import org.example.grab.domain.payment.service.OrderCancelService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PublicIdParser;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 결제 후 취소는 PG 결제 취소가 필요하므로 주문 취소 API를 결제 도메인에 둔다. 결제 API와 같은 경로 구조를 쓴다.
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderCancelController {

    private final OrderCancelService orderCancelService;
    private final CurrentUserIdProvider currentUserIdProvider;

    // 취소가 끝났거나 결제 취소 결과를 확인 중이면 200으로 응답하고 status·refundStatus로 구분한다(ORDER.md 1.4).
    @PostMapping("/{orderId}/cancel")
    public ApiResponse<OrderCancelResponse> cancel(
            @PathVariable String orderId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderCancelRequest request
    ) {
        return ApiResponse.success(orderCancelService.cancel(
                currentUserIdProvider.currentUserId(),
                PublicIdParser.parse(orderId),
                idempotencyKey,
                request
        ));
    }
}
