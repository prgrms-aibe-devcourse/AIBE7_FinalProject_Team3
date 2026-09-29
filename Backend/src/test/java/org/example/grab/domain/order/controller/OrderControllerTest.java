package org.example.grab.domain.order.controller;

import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.dto.OrderCreateResponse;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.service.OrderCreateService;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.security.CurrentUserIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTest {

    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";

    private OrderCreateService orderCreateService;
    private CurrentUserIdProvider currentUserIdProvider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderCreateService = mock(OrderCreateService.class);
        currentUserIdProvider = mock(CurrentUserIdProvider.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderCreateService, currentUserIdProvider))
                .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
                .build();
    }

    @Test
    @DisplayName("주문 생성은 201과 서버 계산 금액을 반환한다")
    void createsOrder() throws Exception {
        // given
        UUID orderId = UUID.randomUUID();
        OrderCreateResponse response = new OrderCreateResponse(
                orderId,
                "ORD-20260928-ABC",
                OrderStatus.PAYMENT_PENDING,
                List.of(new OrderCreateResponse.Item(1001L, "상품", "블랙 / M", 129000, 2, 258000)),
                258000,
                3000,
                261000,
                OffsetDateTime.parse("2026-09-28T12:10:00Z")
        );
        given(currentUserIdProvider.currentUserId()).willReturn(1L);
        given(orderCreateService.create(eq(1L), eq(IDEMPOTENCY_KEY), any(OrderCreateRequest.class)))
                .willReturn(response);

        // when & then
        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.items[0].subtotal").value(258000))
                .andExpect(jsonPath("$.data.shippingAmount").value(3000))
                .andExpect(jsonPath("$.data.totalAmount").value(261000));
    }

    @Test
    @DisplayName("수량과 배송지 필수값을 검증한다")
    void validatesRequest() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dropId":100,"items":[{"optionId":1001,"quantity":0}],
                                 "shippingAddress":{"recipient":"","phone":"","postalCode":"","address1":""}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private String validRequest() {
        return """
                {"dropId":100,"items":[{"optionId":1001,"quantity":2}],
                 "shippingAddress":{"recipient":"홍길동","phone":"01012345678",
                 "postalCode":"06236","address1":"서울시 강남구","address2":"101호"}}
                """;
    }
}
