package org.example.grab.domain.order.controller;

import org.example.grab.domain.order.dto.OrderItemResponse;
import org.example.grab.domain.order.dto.OrderShippingResponse;
import org.example.grab.domain.order.dto.SellerOrderDetailResponse;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.service.SellerOrderService;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.global.error.GlobalExceptionHandler;
import org.example.grab.global.error.ValidationErrorCodeResolver;
import org.example.grab.global.security.identity.CurrentSellerIdProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellerOrderControllerTest {

    private final SellerOrderService sellerOrderService = mock(SellerOrderService.class);
    private final CurrentSellerIdProvider currentSellerIdProvider = mock(CurrentSellerIdProvider.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SellerOrderController(sellerOrderService, currentSellerIdProvider))
            .setControllerAdvice(new GlobalExceptionHandler(new ValidationErrorCodeResolver(List.of())))
            .build();

    @Test
    @DisplayName("판매자 주문 상세는 주문·결제·배송 상태를 문자열로 반환한다")
    void findsOrderDetail() throws Exception {
        // given
        UUID orderId = UUID.randomUUID();
        SellerOrderDetailResponse response = new SellerOrderDetailResponse(
                orderId,
                "ORD-20260929-ABC",
                OrderStatus.DELIVERED,
                List.of(new OrderItemResponse("한정 상품", "검정 / L", 15000, 2, 30000)),
                30000,
                3000,
                33000,
                PaymentStatus.SUCCEEDED,
                new OrderShippingResponse(OrderStatus.DELIVERED, "CJ", "1234567890",
                        OffsetDateTime.parse("2026-09-30T09:00:00Z")),
                OffsetDateTime.parse("2026-09-29T12:00:00Z")
        );
        given(currentSellerIdProvider.currentSellerId()).willReturn(7L);
        given(sellerOrderService.findOrder(7L, orderId)).willReturn(response);

        // when
        var result = mockMvc.perform(get("/api/v1/seller/orders/{orderId}", orderId));

        // then
        result
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.paymentStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.items[0].subtotal").value(30000))
                .andExpect(jsonPath("$.data.shipping.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.shipping.carrier").value("CJ"))
                .andExpect(jsonPath("$.data.shipping.trackingNumber").value("1234567890"))
                .andExpect(jsonPath("$.data.shipping.deliveredAt").exists());
    }

    @Test
    void rejectsInvalidStatusParameter() throws Exception {
        // given
        var request = get("/api/v1/seller/orders").param("orderStatus", "NOT_A_STATUS");

        // when
        var result = mockMvc.perform(request);

        // then
        result
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsInvalidPageParameters() throws Exception {
        // given
        var request = get("/api/v1/seller/orders").param("page", "-1");

        // when
        var result = mockMvc.perform(request);

        // then
        result
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsNotFoundForInvalidOrderId() throws Exception {
        // given
        var request = get("/api/v1/seller/orders/not-a-uuid");

        // when
        var result = mockMvc.perform(request);

        // then
        result
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }
}
