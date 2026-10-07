package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.example.grab.domain.payment.dto.OrderCancelRequest;
import org.example.grab.domain.payment.dto.OrderCancelResponse;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.dto.RefundStatus;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 결제 전 주문 취소(ORDER.md 1.4, ERD.md 3.3)가 실제 PostgreSQL에서 주문·예약·재고를 한 번만 바꾸는지 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderCancelServiceIntegrationTest {

    private static final OrderCancelRequest REQUEST = new OrderCancelRequest("단순 변심");

    @Autowired
    private OrderCancelService orderCancelService;

    @Autowired
    private PaymentTransactionService transactionService;

    @Autowired
    private OrderPaymentService orderPaymentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    private PaymentTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new PaymentTestFixture(jdbcTemplate, orderRepository, orderItemRepository, stockReservationRepository);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    @Test
    @DisplayName("결제 전 주문을 취소하면 주문을 CANCELED로 바꾸고 선점 재고를 가용 재고로 돌려준다")
    void cancelUnpaidOrder() {
        // given
        Order order = fixture.createOrder();

        // when
        OrderCancelResponse response = cancel(order, newKey());

        // then
        assertThat(response.orderId()).isEqualTo(order.getUuid());
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(response.refundStatus()).isEqualTo(RefundStatus.NONE);
        assertThat(response.canceledAt()).isNotNull();
        assertThat(orderRow(order))
                .containsEntry("status", "CANCELED")
                .containsEntry("cancel_reason", "단순 변심");
        assertThat(reservationRow(order))
                .containsEntry("status", "RELEASED")
                .containsEntry("release_reason", "ORDER_CANCELED")
                .containsEntry("release_destination", "AVAILABLE");
        assertThat(fixture.quantities()).containsEntry("reserved_quantity", 0).containsEntry("sold_quantity", 0);
    }

    @Test
    @DisplayName("같은 키와 같은 본문으로 다시 요청하면 최초 결과를 돌려주고 재고는 한 번만 반환한다")
    void replaysSameRequest() {
        // given
        Order order = fixture.createOrder();
        String key = newKey();
        OrderCancelResponse first = cancel(order, key);

        // when
        OrderCancelResponse second = cancel(order, key);

        // then
        assertThat(second).isEqualTo(first);
        assertThat(fixture.quantities()).containsEntry("reserved_quantity", 0);
    }

    @Test
    @DisplayName("같은 키에 다른 본문이면 DUPLICATE_IDEMPOTENCY_KEY, 이미 취소된 주문에 다른 키면 ORDER_NOT_CANCELABLE로 거부한다")
    void rejectsConflictingRequests() {
        // given
        Order order = fixture.createOrder();
        String key = newKey();
        cancel(order, key);

        // when & then
        assertThatThrownBy(() -> orderCancelService.cancel(
                fixture.buyerId(), order.getUuid(), key, new OrderCancelRequest("다른 사유")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
        assertThatThrownBy(() -> cancel(order, newKey()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.ORDER_NOT_CANCELABLE);
    }

    @Test
    @DisplayName("다른 사용자의 주문은 존재 여부를 숨기고 ORDER_NOT_FOUND로 거부한다")
    void hidesOtherBuyersOrder() {
        // given
        Order order = fixture.createOrder();

        // when & then
        assertThatThrownBy(() -> orderCancelService.cancel(fixture.buyerId() + 1_000_000, order.getUuid(), newKey(), REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    @DisplayName("만료된 주문은 취소하지 않고, 마감이 지났지만 아직 만료 처리 전인 주문은 취소한다")
    void expiredAndDueOrders() {
        // given
        Order expired = fixture.createOrder();
        orderPaymentService.expireIfDue(expired.getId(), expired.getPaymentExpiresAt());
        Order due = fixture.createOrder();
        jdbcTemplate.update("""
                UPDATE orders SET created_at = now() - interval '20 minutes', payment_expires_at = now() - interval '5 minutes'
                WHERE id = ?
                """, due.getId());

        // when & then
        assertThatThrownBy(() -> cancel(expired, newKey()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.ORDER_NOT_CANCELABLE);
        assertThat(cancel(due, newKey()).status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.quantities()).containsEntry("reserved_quantity", 0);
    }

    @Test
    @DisplayName("승인 응답을 기다리는 결제가 있으면 ORDER_STATUS_CONFLICT로 거부하고 아무것도 바꾸지 않는다")
    void rejectsWhilePaymentPending() {
        // given
        Order order = fixture.createOrder();
        preparePayment(order);

        // when & then
        assertThatThrownBy(() -> cancel(order, newKey()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.ORDER_STATUS_CONFLICT);
        assertThat(orderRow(order))
                .containsEntry("status", "PAYMENT_PENDING")
                .containsEntry("cancel_idempotency_key", null);
        assertThat(fixture.quantities()).containsEntry("reserved_quantity", PaymentTestFixture.QUANTITY);
    }

    @Test
    @DisplayName("결과 불명 결제가 있어도 취소하고, 이후 승인이 확인되면 주문은 그대로 두고 보정 대상으로 남긴다")
    void cancelsWithUnknownPaymentAndFlagsLateApproval() {
        // given
        Order order = fixture.createOrder();
        Long paymentId = preparePayment(order);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PaymentGatewayResult unknown = PaymentGatewayResult.unknown(null, null, "응답 유실");
        transactionService.applyResult(paymentId, unknown, unknown, now);

        // when
        OrderCancelResponse response = cancel(order, newKey());
        transactionService.applyResult(paymentId, PaymentGatewayResult.approved(
                "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT, now), null, now);

        // then: 늦게 확인된 승인은 취소된 주문을 되살리지 않고 환불 대상으로 남는다
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.payments(order)).singleElement().satisfies(payment -> {
            assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
            assertThat(payment.get("reconciliation_status")).isEqualTo("REQUIRED");
        });
        assertThat(fixture.quantities()).containsEntry("reserved_quantity", 0).containsEntry("sold_quantity", 0);
    }

    @Test
    @DisplayName("같은 키로 취소 요청이 동시에 와도 한 번만 취소하고 모두 같은 결과를 받는다")
    void concurrentSameKeyRequests() throws Exception {
        // given
        Order order = fixture.createOrder();
        String key = newKey();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderCancelResponse>> futures = new ArrayList<>();

        try {
            // when
            for (int i = 0; i < 3; i++) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return cancel(order, key);
                }));
            }
            start.countDown();
            List<OrderCancelResponse> responses = new ArrayList<>();
            for (Future<OrderCancelResponse> future : futures) {
                responses.add(future.get(10, TimeUnit.SECONDS));
            }

            // then
            assertThat(responses).allSatisfy(response -> assertThat(response).isEqualTo(responses.get(0)));
            assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.CANCELED);
            assertThat(fixture.quantities()).containsEntry("reserved_quantity", 0);
        } finally {
            executor.shutdownNow();
        }
    }

    private OrderCancelResponse cancel(Order order, String key) {
        return orderCancelService.cancel(fixture.buyerId(), order.getUuid(), key, REQUEST);
    }

    // 결제 시도가 PENDING으로 저장되고 PG 승인 응답을 기다리는 상태를 만든다.
    private Long preparePayment(Order order) {
        PaymentPreparation.Ready ready = (PaymentPreparation.Ready) transactionService.prepare(
                fixture.buyerId(), order.getUuid(), IdempotencyKey.from(newKey()), RequestHash.from("c".repeat(64)),
                new PaymentRequest("payment-" + UUID.randomUUID(), PaymentTestFixture.TOTAL_AMOUNT),
                OffsetDateTime.now(ZoneOffset.UTC));
        return ready.paymentId();
    }

    private Map<String, Object> orderRow(Order order) {
        return jdbcTemplate.queryForMap(
                "SELECT status, cancel_reason, cancel_idempotency_key FROM orders WHERE id = ?", order.getId());
    }

    private Map<String, Object> reservationRow(Order order) {
        return jdbcTemplate.queryForMap("""
                SELECT r.status, r.release_reason, r.release_destination
                FROM stock_reservations r JOIN order_items i ON i.id = r.order_item_id
                WHERE i.order_id = ?
                """, order.getId());
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
