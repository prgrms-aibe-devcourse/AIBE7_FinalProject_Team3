package org.example.grab.domain.payment.controller;

import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.payment.dto.OrderCancelRequest;
import org.example.grab.domain.payment.dto.OrderCancelResponse;
import org.example.grab.domain.payment.dto.RefundStatus;
import org.example.grab.domain.payment.service.OrderCancelService;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.security.identity.CurrentUserIdProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderCancelControllerTest {

    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";

    private final OrderCancelService orderCancelService = mock(OrderCancelService.class);
    private final CurrentUserIdProvider currentUserIdProvider = mock(CurrentUserIdProvider.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new OrderCancelController(orderCancelService, currentUserIdProvider))
            .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
            .build();

    @Test
    @DisplayName("취소 결과를 200과 주문 상태·환불 상태로 응답한다")
    void cancel() throws Exception {
        // given
        UUID orderId = UUID.randomUUID();
        OffsetDateTime canceledAt = OffsetDateTime.parse("2026-10-06T05:10:00Z");
        given(currentUserIdProvider.currentUserId()).willReturn(1L);
        given(orderCancelService.cancel(eq(1L), eq(orderId), eq(IDEMPOTENCY_KEY), eq(new OrderCancelRequest("단순 변심"))))
                .willReturn(new OrderCancelResponse(orderId, OrderStatus.CANCELED, RefundStatus.NONE, canceledAt));

        // when
        var result = mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason":"단순 변심"}
                        """));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.refundStatus").value("NONE"))
                .andExpect(jsonPath("$.data.canceledAt").exists());
    }

    @Test
    @DisplayName("취소 사유가 없거나 공백이거나 500자를 넘으면 VALIDATION_FAILED로 거부한다")
    void rejectsInvalidReason() throws Exception {
        for (String body : List.of("{}", "{\"reason\":\" \"}", "{\"reason\":\"" + "가".repeat(501) + "\"}")) {
            // when
            var result = mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", UUID.randomUUID())
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));

            // then
            result.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(orderCancelService);
    }

    @Test
    @DisplayName("주문 ID 형식이 올바르지 않으면 RESOURCE_NOT_FOUND로 거부한다")
    void rejectsInvalidOrderId() throws Exception {
        // when
        var result = mockMvc.perform(post("/api/v1/orders/not-a-uuid/cancel")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason":"단순 변심"}
                        """));

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        verifyNoInteractions(orderCancelService);
    }
}
