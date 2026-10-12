package org.example.grab.domain.payment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "주문 취소", description = "구매자의 주문 취소와 결제 취소·재고 반환")
public class OrderCancelController {

    private final OrderCancelService orderCancelService;
    private final CurrentUserIdProvider currentUserIdProvider;

    // 취소가 끝났거나 결제 취소 결과를 확인 중이면 200으로 응답하고 status·refundStatus로 구분한다(ORDER.md 1.4).
    @Operation(summary = "주문 취소",
            description = "PAYMENT_PENDING·PAID·PREPARING 주문을 취소한다. 결제한 주문은 토스페이먼츠 테스트 결제를 취소한다. Idempotency-Key(UUID) 헤더가 필요하다. 결제 취소 결과를 확인 중이면 200과 refundStatus: UNKNOWN으로 응답한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "취소 완료 또는 결제 취소 결과 확인 중")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, INVALID_IDEMPOTENCY_KEY, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED (CSRF 토큰 누락)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND (UUID 형식 오류), ORDER_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_IDEMPOTENCY_KEY, ORDER_NOT_CANCELABLE, ORDER_STATUS_CONFLICT")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "PAYMENT_CANCEL_FAILED")
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
