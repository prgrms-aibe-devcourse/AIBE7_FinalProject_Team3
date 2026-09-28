package org.example.grab.domain.order.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.dto.OrderCreateResponse;
import org.example.grab.domain.order.service.OrderCreateService;
import org.example.grab.global.common.ApiResponse;
import org.example.grab.global.security.CurrentUserIdProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderCreateService orderCreateService;
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
}
