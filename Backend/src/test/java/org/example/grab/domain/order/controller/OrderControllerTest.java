package org.example.grab.domain.order.controller;

import org.example.grab.domain.order.dto.MyOrderDetailResponse;
import org.example.grab.domain.order.dto.MyOrderListResponse;
import org.example.grab.domain.order.dto.OrderCreateRequest;
import org.example.grab.domain.order.dto.OrderCreateResponse;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.service.OrderCreateService;
import org.example.grab.domain.order.service.OrderQueryService;
import org.example.grab.global.common.PageResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.GlobalExceptionHandler;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTest {

    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";

    private OrderCreateService orderCreateService;
    private OrderQueryService orderQueryService;
    private CurrentUserIdProvider currentUserIdProvider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderCreateService = mock(OrderCreateService.class);
        orderQueryService = mock(OrderQueryService.class);
        currentUserIdProvider = mock(CurrentUserIdProvider.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderCreateService, orderQueryService, currentUserIdProvider))
                .setControllerAdvice(new GlobalExceptionHandler())
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

    @Test
    @DisplayName("내 주문 목록은 상태 필터와 페이지 정보를 서비스에 전달한다")
    void findsMyOrders() throws Exception {
        // given
        UUID orderId = UUID.randomUUID();
        PageResponse<MyOrderListResponse> response = new PageResponse<>(
                List.of(new MyOrderListResponse(
                        orderId,
                        "ORD-20260928-ABC",
                        OrderStatus.PAID,
                        261000,
                        OffsetDateTime.parse("2026-09-28T12:00:00Z")
                )),
                0,
                20,
                1,
                1,
                false
        );
        given(currentUserIdProvider.currentUserId()).willReturn(1L);
        given(orderQueryService.findMyOrders(1L, OrderStatus.PAID, 0, 20)).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/orders").param("status", "PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.content[0].status").value("PAID"))
                .andExpect(jsonPath("$.data.content[0].totalAmount").value(261000))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("내 주문 목록의 페이지 범위와 상태 값을 검증한다")
    void rejectsInvalidListParameters() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/orders").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("내 주문 상세는 금액 내역과 결제 마감 시각을 반환한다")
    void findsMyOrder() throws Exception {
        // given
        UUID orderId = UUID.randomUUID();
        MyOrderDetailResponse response = new MyOrderDetailResponse(
                orderId,
                "ORD-20260928-ABC",
                OrderStatus.PAYMENT_PENDING,
                List.of(new MyOrderDetailResponse.Item("상품", "블랙 / M", 129000, 2, 258000)),
                258000,
                3000,
                261000,
                "PENDING",
                OffsetDateTime.parse("2026-09-28T12:10:00Z"),
                new MyOrderDetailResponse.Shipping(null, null, null),
                OffsetDateTime.parse("2026-09-28T12:00:00Z")
        );
        given(currentUserIdProvider.currentUserId()).willReturn(1L);
        given(orderQueryService.findMyOrder(1L, orderId)).willReturn(response);

        // when & then
        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].optionName").value("블랙 / M"))
                .andExpect(jsonPath("$.data.itemsAmount").value(258000))
                .andExpect(jsonPath("$.data.shippingAmount").value(3000))
                .andExpect(jsonPath("$.data.totalAmount").value(261000))
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.paymentExpiresAt").exists())
                .andExpect(jsonPath("$.data.shipping.trackingNumber").doesNotExist());
    }

    @Test
    @DisplayName("다른 구매자의 주문이나 없는 주문은 ORDER_NOT_FOUND로 응답한다")
    void returnsNotFoundForOthersOrder() throws Exception {
        // given
        UUID orderId = UUID.randomUUID();
        given(currentUserIdProvider.currentUserId()).willReturn(1L);
        given(orderQueryService.findMyOrder(1L, orderId))
                .willThrow(new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("형식이 잘못된 주문 ID는 RESOURCE_NOT_FOUND로 응답한다")
    void returnsNotFoundForInvalidOrderId() throws Exception {
        mockMvc.perform(get("/api/v1/orders/not-a-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    private String validRequest() {
        return """
                {"dropId":100,"items":[{"optionId":1001,"quantity":2}],
                 "shippingAddress":{"recipient":"홍길동","phone":"01012345678",
                 "postalCode":"06236","address1":"서울시 강남구","address2":"101호"}}
                """;
    }
}
