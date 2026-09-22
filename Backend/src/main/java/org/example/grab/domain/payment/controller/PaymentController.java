package org.example.grab.domain.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.payment.dto.PaymentHistoryResponse;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.dto.PaymentResponse;
import org.example.grab.domain.payment.service.PaymentService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.CurrentUserId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders/{orderId}/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ApiResponse<PaymentResponse> pay(
            @CurrentUserId Long buyerId,
            @PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request
    ) {
        return ApiResponse.success(paymentService.pay(buyerId, orderId, idempotencyKey, request));
    }

    @GetMapping
    public ApiResponse<List<PaymentHistoryResponse>> findHistory(
            @CurrentUserId Long userId,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(paymentService.findHistory(userId, orderId));
    }
}
