package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderItem;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.entity.ShippingAddress;
import org.example.grab.domain.order.entity.StockReservation;
import org.example.grab.domain.order.error.OrderErrorCode;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.dto.PaymentResponse;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.entity.ReconciliationStatus;
import org.example.grab.domain.payment.error.PaymentErrorCode;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 실제 PostgreSQL에서 결제 요청 → PG 결과 반영 흐름을 확인한다. PG는 결과를 지정할 수 있는 mock으로 바꾼다.
// 서비스의 두 트랜잭션이 운영처럼 따로 커밋되도록 테스트 트랜잭션을 쓰지 않고, 만든 데이터는 테스트마다 지운다.
@SpringBootTest
class PaymentServiceIntegrationTest {

    private static final long TOTAL_AMOUNT = 33000;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private Long buyerId;
    private Long sellerId;
    private Long dropId;
    private Long optionId;
    private Order order;
    private OffsetDateTime approvedAt;

    @BeforeEach
    void setUp() {
        String uniqueValue = UUID.randomUUID().toString();
        buyerId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'encoded', ?) RETURNING id",
                Long.class, uniqueValue + "@example.com", "b" + uniqueValue.substring(0, 8));
        sellerId = jdbcTemplate.queryForObject(
                "INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, 'GRAB 판매자', ?) RETURNING id",
                Long.class, buyerId, "seller-" + uniqueValue + "@example.com");
        dropId = jdbcTemplate.queryForObject(
                "INSERT INTO drops (seller_id) VALUES (?) RETURNING id", Long.class, sellerId);
        optionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO drop_options (drop_id, unit_price, total_quantity, reserved_quantity)
                VALUES (?, 15000, 10, 2)
                RETURNING id
                """,
                Long.class, dropId);

        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15).truncatedTo(ChronoUnit.MICROS);
        order = orderRepository.saveAndFlush(Order.create(
                "ORD-PAY-" + uniqueValue, buyerId, dropId, "order-key-" + uniqueValue, "a".repeat(64),
                "한정판 후드", "GRAB 판매자", 30000, 3000,
                ShippingAddress.of("홍길동", "010-1234-5678", "06236", "서울시 강남구", "101호", null),
                expiresAt));
        OrderItem item = orderItemRepository.saveAndFlush(OrderItem.create(order, optionId, "검정 / L", 15000, 2));
        stockReservationRepository.saveAndFlush(StockReservation.hold(item, expiresAt));
        approvedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM payment_events WHERE payment_id IN (SELECT id FROM payments WHERE order_id = ?)",
                order.getId());
        jdbcTemplate.update("DELETE FROM payments WHERE order_id = ?", order.getId());
        jdbcTemplate.update("DELETE FROM stock_reservations WHERE order_item_id IN " +
                "(SELECT id FROM order_items WHERE drop_id = ?)", dropId);
        jdbcTemplate.update("DELETE FROM order_items WHERE drop_id = ?", dropId);
        jdbcTemplate.update("DELETE FROM orders WHERE drop_id = ?", dropId);
        jdbcTemplate.update("DELETE FROM drop_options WHERE drop_id = ?", dropId);
        jdbcTemplate.update("DELETE FROM drops WHERE id = ?", dropId);
        jdbcTemplate.update("DELETE FROM sellers WHERE id = ?", sellerId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", buyerId);
    }

    @Test
    @DisplayName("승인 성공은 결제 SUCCEEDED, 주문 PAID, 재고 확정과 승인 이벤트를 남기고 서버 PG 키로 승인을 요청한다")
    void approvedPayment() {
        // given
        given(paymentGateway.confirm(any())).willReturn(approved());
        String clientKey = UUID.randomUUID().toString();

        // when
        PaymentResponse response = pay(clientKey, "payment-key", TOTAL_AMOUNT);

        // then
        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(response.reconciliationStatus()).isEqualTo(ReconciliationStatus.NONE);
        assertThat(response.orderId()).isEqualTo(order.getUuid());
        assertThat(response.orderNumber()).isEqualTo(order.getOrderNumber());
        assertThat(response.amount()).isEqualTo(TOTAL_AMOUNT);
        assertThat(response.paidAt()).isEqualTo(approvedAt);
        assertThat(response.failure()).isNull();
        assertThat(orderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(quantities()).containsEntry("reserved_quantity", 0).containsEntry("sold_quantity", 2);
        assertThat(eventKeys()).containsExactly("confirm");

        ArgumentCaptor<PaymentConfirmCommand> command = ArgumentCaptor.forClass(PaymentConfirmCommand.class);
        verify(paymentGateway).confirm(command.capture());
        assertThat(command.getValue().orderId()).isEqualTo(order.getOrderNumber());
        assertThat(command.getValue().amount()).isEqualTo(TOTAL_AMOUNT);
        assertThat(command.getValue().idempotencyKey()).isNotEqualTo(clientKey);
        verify(paymentGateway, never()).lookup(anyString());
    }

    @Test
    @DisplayName("같은 멱등 키·같은 요청은 PG를 다시 부르지 않고 최초 결과를, 다른 요청은 409를 돌려준다")
    void idempotentReplay() {
        // given
        given(paymentGateway.confirm(any())).willReturn(approved());
        String clientKey = UUID.randomUUID().toString();
        PaymentResponse first = pay(clientKey, "payment-key", TOTAL_AMOUNT);

        // when
        PaymentResponse replay = pay(clientKey, "payment-key", TOTAL_AMOUNT);

        // then
        assertThat(replay.paymentId()).isEqualTo(first.paymentId());
        assertThat(replay.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(paymentGateway, times(1)).confirm(any());
        assertThatThrownBy(() -> pay(clientKey, "other-payment-key", TOTAL_AMOUNT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.DUPLICATE_IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("PG가 거절하면 결제 FAILED와 실패 정보를 남기고 주문은 결제 대기로 두어 새 결제를 허용한다")
    void rejectedThenRetry() {
        // given
        given(paymentGateway.confirm(any()))
                .willReturn(PaymentGatewayResult.notApproved(null, "REJECT_CARD_PAYMENT", "카드 승인이 거절되었습니다."))
                .willReturn(approved());

        // when
        PaymentResponse rejected = pay(UUID.randomUUID().toString(), "payment-key-1", TOTAL_AMOUNT);
        OrderStatus afterRejected = orderStatus();
        PaymentResponse retried = pay(UUID.randomUUID().toString(), "payment-key-2", TOTAL_AMOUNT);

        // then
        assertThat(rejected.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(rejected.failure().code()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(rejected.paidAt()).isNull();
        assertThat(afterRejected).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(retried.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(orderStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("승인 결과가 불명이면 바로 조회하고, 조회로 승인이 확인되면 성공으로 반영한다")
    void unknownResolvedByLookup() {
        // given
        given(paymentGateway.confirm(any())).willReturn(PaymentGatewayResult.unknown(null, null, "타임아웃"));
        given(paymentGateway.lookup("payment-key")).willReturn(approved());

        // when
        PaymentResponse response = pay(UUID.randomUUID().toString(), "payment-key", TOTAL_AMOUNT);

        // then
        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(orderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(eventKeys()).hasSize(2).contains("confirm").anyMatch(key -> key.startsWith("lookup:"));
    }

    @Test
    @DisplayName("조회로도 결과를 모르면 UNKNOWN·보정 필요로 남기고, 다음 요청에서 조회로 정리한 뒤 새 결제를 진행한다")
    void unknownBlocksUntilResolved() {
        // given
        given(paymentGateway.confirm(any()))
                .willReturn(PaymentGatewayResult.unknown(null, null, "타임아웃"))
                .willReturn(approved());
        given(paymentGateway.lookup("payment-key-1"))
                .willReturn(PaymentGatewayResult.unknown(null, null, "조회 실패"))
                .willReturn(PaymentGatewayResult.notApproved(null, "NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다."));

        // when
        PaymentResponse unknown = pay(UUID.randomUUID().toString(), "payment-key-1", TOTAL_AMOUNT);
        PaymentResponse next = pay(UUID.randomUUID().toString(), "payment-key-2", TOTAL_AMOUNT);

        // then
        assertThat(unknown.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(unknown.reconciliationStatus()).isEqualTo(ReconciliationStatus.REQUIRED);
        assertThat(next.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentStatuses()).containsExactly("FAILED", "SUCCEEDED");
        assertThat(orderStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("다른 구매자의 주문, 주문 금액과 다른 요청, 결제 완료된 주문은 PG를 부르지 않고 거부한다")
    void rejectsInvalidRequests() {
        // given
        given(paymentGateway.confirm(any())).willReturn(approved());

        // when & then
        assertThatThrownBy(() -> paymentService.pay(buyerId + 1, order.getUuid(), UUID.randomUUID().toString(),
                new PaymentRequest("payment-key", TOTAL_AMOUNT)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
        assertThatThrownBy(() -> pay(UUID.randomUUID().toString(), "payment-key", TOTAL_AMOUNT - 1))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        verify(paymentGateway, never()).confirm(any());

        pay(UUID.randomUUID().toString(), "payment-key", TOTAL_AMOUNT);
        assertThatThrownBy(() -> pay(UUID.randomUUID().toString(), "payment-key-2", TOTAL_AMOUNT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
    }

    @Test
    @DisplayName("멱등 키 헤더가 없거나 UUID가 아니면 INVALID_IDEMPOTENCY_KEY로 거부한다")
    void rejectsInvalidIdempotencyKey() {
        // when & then
        assertThatThrownBy(() -> pay(null, "payment-key", TOTAL_AMOUNT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_IDEMPOTENCY_KEY);
        assertThatThrownBy(() -> pay("not-a-uuid", "payment-key", TOTAL_AMOUNT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_IDEMPOTENCY_KEY);
    }

    private PaymentResponse pay(String clientKey, String paymentKey, long amount) {
        return paymentService.pay(buyerId, order.getUuid(), clientKey, new PaymentRequest(paymentKey, amount));
    }

    private PaymentGatewayResult approved() {
        return PaymentGatewayResult.approved("DONE", order.getOrderNumber(), TOTAL_AMOUNT, approvedAt);
    }

    private OrderStatus orderStatus() {
        return OrderStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, order.getId()));
    }

    private Map<String, Object> quantities() {
        return jdbcTemplate.queryForMap(
                "SELECT reserved_quantity, sold_quantity FROM drop_options WHERE id = ?", optionId);
    }

    private List<String> paymentStatuses() {
        return jdbcTemplate.queryForList(
                "SELECT status FROM payments WHERE order_id = ? ORDER BY id", String.class, order.getId());
    }

    private List<String> eventKeys() {
        return jdbcTemplate.queryForList(
                """
                SELECT e.event_key FROM payment_events e JOIN payments p ON p.id = e.payment_id
                WHERE p.order_id = ? ORDER BY e.id
                """,
                String.class, order.getId());
    }
}
