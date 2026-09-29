package org.example.grab.domain.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.dto.PaymentResponse;
import org.example.grab.domain.payment.service.PaymentService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.common.PublicIdParser;
import org.example.grab.global.security.CurrentUserIdProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class PaymentController {

    private final PaymentService paymentService;
    private final CurrentUserIdProvider currentUserIdProvider;

    // 결제 시도가 만들어진 뒤의 결과(SUCCEEDED, FAILED, UNKNOWN)는 모두 200으로 응답하고 status로 구분한다(PAYMENT.md 1.1).
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
