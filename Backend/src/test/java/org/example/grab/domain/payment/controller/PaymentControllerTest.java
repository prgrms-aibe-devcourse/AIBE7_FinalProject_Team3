package org.example.grab.domain.payment.controller;

import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.dto.PaymentResponse;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.entity.ReconciliationStatus;
import org.example.grab.domain.payment.service.PaymentService;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.security.CurrentUserIdProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerTest {

    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";

    private final PaymentService paymentService = mock(PaymentService.class);
    private final CurrentUserIdProvider currentUserIdProvider = mock(CurrentUserIdProvider.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PaymentController(paymentService, currentUserIdProvider))
            .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
            .build();

    @Test
    @DisplayName("결제 결과를 200과 결제 상태로 응답한다")
    void pay() throws Exception {
        // given
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentResponse response = new PaymentResponse(
                paymentId, orderId, "ORD-20260929-000001", 33000, PaymentStatus.FAILED, ReconciliationStatus.NONE,
                null, new PaymentResponse.Failure("REJECT_CARD_PAYMENT", "카드 승인이 거절되었습니다."));
        given(currentUserIdProvider.currentUserId()).willReturn(1L);
        given(paymentService.pay(eq(1L), eq(orderId), eq(IDEMPOTENCY_KEY), any(PaymentRequest.class)))
                .willReturn(response);

        // when
        var result = mockMvc.perform(post("/api/v1/orders/{orderId}/payments", orderId)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paymentKey":"payment-key","amount":33000}
                        """));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.data.orderNumber").value("ORD-20260929-000001"))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.reconciliationStatus").value("NONE"))
                .andExpect(jsonPath("$.data.paidAt").doesNotExist())
                .andExpect(jsonPath("$.data.failure.code").value("REJECT_CARD_PAYMENT"));
    }

    @Test
    @DisplayName("paymentKey·amount가 없거나 amount가 0 이하면 VALIDATION_FAILED로 거부한다")
    void rejectsInvalidBody() throws Exception {
        // when
        var result = mockMvc.perform(post("/api/v1/orders/{orderId}/payments", UUID.randomUUID())
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paymentKey":" ","amount":0}
                        """));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("주문 ID 형식이 올바르지 않으면 RESOURCE_NOT_FOUND로 거부한다")
    void rejectsInvalidOrderId() throws Exception {
        // when
        var result = mockMvc.perform(post("/api/v1/orders/not-a-uuid/payments")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paymentKey":"payment-key","amount":33000}
                        """));

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("결제 요청의 문자열 표현에 paymentKey 원문을 담지 않는다")
    void masksPaymentKey() {
        // when
        String text = new PaymentRequest("secret-payment-key", 33000L).toString();

        // then
        assertThat(text).doesNotContain("secret-payment-key").contains("33000");
    }
}
