package org.example.grab.domain.payment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.dto.PaymentResponse;
import org.example.grab.domain.payment.service.PaymentService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PublicIdParser;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
@Tag(name = "결제", description = "토스페이먼츠 테스트 환경 Mock 결제 승인")
public class PaymentController {

    private final PaymentService paymentService;
    private final CurrentUserIdProvider currentUserIdProvider;

    // 결제 시도가 만들어진 뒤의 결과(SUCCEEDED, FAILED, UNKNOWN)는 모두 200으로 응답하고 status로 구분한다(PAYMENT.md 1.1).
    @Operation(summary = "Mock 결제 요청",
            description = "결제창에서 받은 paymentKey로 토스페이먼츠 테스트 결제를 승인한다. 실제 결제는 일어나지 않는다. Idempotency-Key(UUID) 헤더가 필요하다. 승인 결과는 200의 status(SUCCEEDED·FAILED·UNKNOWN·PENDING)로 구분하며, UNKNOWN이면 같은 키와 본문으로 다시 보내 결과를 확인한다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "결제 시도 결과 반환")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_REQUEST, INVALID_IDEMPOTENCY_KEY, VALIDATION_FAILED")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED (CSRF 토큰 누락)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND (UUID 형식 오류), ORDER_NOT_FOUND")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_IDEMPOTENCY_KEY, PAYMENT_ALREADY_PROCESSED, INVALID_STATE_TRANSITION")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "PAYMENT_AMOUNT_MISMATCH, PAYMENT_EXPIRED")
    @PostMapping("/{orderId}/payments")
    public ApiResponse<PaymentResponse> pay(
            @PathVariable String orderId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request
    ) {
        return ApiResponse.success(paymentService.pay(
                currentUserIdProvider.currentUserId(),
                PublicIdParser.parse(orderId),
                idempotencyKey,
                request
        ));
    }
}
