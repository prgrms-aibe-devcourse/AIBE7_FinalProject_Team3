package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.example.grab.global.idempotency.IdempotencyKey;
import org.example.grab.global.idempotency.RequestHash;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 진행 중 결제 정리 배치(ERD.md 3.2)가 대상을 선점하고 PG 조회로 확정하는지 실제 DB로 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentInProgressCleanupIntegrationTest {

    @Autowired
    private PaymentInProgressCleanupService cleanupService;

    @Autowired
    private PaymentTransactionService transactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private PaymentTestFixture fixture;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        fixture = new PaymentTestFixture(jdbcTemplate, orderRepository, orderItemRepository, stockReservationRepository);
        now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    @Test
    @DisplayName("60초 넘게 바뀌지 않은 PENDING·UNKNOWN 결제만 선점하고 updated_at을 바꿔, 바로 다시 선점하지 않는다")
    void claimsOnlyStaleInProgressPayments() {
        // given
        Long stalePending = pendingPayment(fixture.createOrder(), now.minusMinutes(2));
        Long freshPending = pendingPayment(fixture.createOrder(), now.minusSeconds(10));
        Long staleUnknown = unknownPayment(fixture.createOrder(), now.minusMinutes(2));
        Long staleFailed = failedPayment(fixture.createOrder(), now.minusMinutes(2));

        // when
        List<Long> claimed = transactionService.claimInProgress(now, 50);

        // then
        assertThat(claimed).contains(stalePending, staleUnknown).doesNotContain(freshPending, staleFailed);
        assertThat(updatedAt(stalePending)).isEqualTo(now);
        assertThat(updatedAt(staleUnknown)).isEqualTo(now);
        assertThat(transactionService.claimInProgress(now.plusSeconds(30), 50))
                .doesNotContain(stalePending, staleUnknown);
        assertThat(transactionService.claimInProgress(now.plusSeconds(60), 50))
                .contains(stalePending, staleUnknown);
    }

    @Test
    @DisplayName("PG가 승인한 PENDING 결제를 확정해 주문을 PAID로 바꾸고, 이벤트 경로를 RECONCILIATION으로 남긴다")
    void settlesApprovedPendingPayment() {
        // given
        Order order = fixture.createOrder();
        Long paymentId = pendingPayment(order, now.minusMinutes(2));
        given(paymentGateway.lookup("payment-" + order.getId())).willReturn(PaymentGatewayResult.approved(
                "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT, now.minusMinutes(1)));

        // when
        cleanupService.cleanUp(now);

        // then
        assertThat(fixture.payments(order)).singleElement()
                .satisfies(payment -> assertThat(payment.get("status")).isEqualTo("SUCCEEDED"));
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAID);
        assertThat(eventSources(paymentId)).containsExactly("LOOKUP:RECONCILIATION");
        verify(paymentGateway, never()).confirm(any());
    }

    @Test
    @DisplayName("승인 요청이 PG에 닿지 않은 UNKNOWN 결제는 주문이 결제 가능하면 같은 서버 멱등 키로 승인을 다시 요청해 확정한다")
    void reconfirmsAwaitingPaymentOfPayableOrder() {
        // given
        Order order = fixture.createOrder();
        Long paymentId = unknownPayment(order, now.minusMinutes(2));
        given(paymentGateway.lookup("payment-" + order.getId()))
                .willReturn(PaymentGatewayResult.awaitingConfirmation("IN_PROGRESS"));
        given(paymentGateway.confirm(any())).willReturn(PaymentGatewayResult.approved(
                "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT, now));

        // when
        cleanupService.cleanUp(now);

        // then
        ArgumentCaptor<PaymentConfirmCommand> command = ArgumentCaptor.forClass(PaymentConfirmCommand.class);
        verify(paymentGateway).confirm(command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM payments WHERE id = ?", String.class, paymentId));
        assertThat(fixture.payments(order)).singleElement().satisfies(payment -> {
            assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
            assertThat(payment.get("reconciliation_status")).isEqualTo("RESOLVED");
        });
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAID);
        assertThat(eventSources(paymentId)).endsWith("LOOKUP:RECONCILIATION", "CONFIRM:RECONCILIATION");
    }

    @Test
    @DisplayName("결제 마감이 지난 주문의 승인 대기 결제는 승인을 다시 요청하지 않고 UNKNOWN으로 남긴다")
    void onlyLooksUpAwaitingPaymentOfExpiredOrder() {
        // given
        Order order = fixture.createOrder();
        unknownPayment(order, now.minusMinutes(2));
        // 마감은 생성 시각보다 뒤여야 하므로(ck_orders_payment_expiry) 생성 시각도 함께 과거로 돌린다.
        jdbcTemplate.update("UPDATE orders SET created_at = ?, payment_expires_at = ? WHERE id = ?",
                now.minusMinutes(20), now.minusSeconds(1), order.getId());
        given(paymentGateway.lookup("payment-" + order.getId()))
                .willReturn(PaymentGatewayResult.awaitingConfirmation("IN_PROGRESS"));

        // when
        int settled = cleanupService.cleanUp(now);

        // then
        verify(paymentGateway, never()).confirm(any());
        assertThat(fixture.payments(order)).singleElement()
                .satisfies(payment -> assertThat(payment.get("status")).isEqualTo("UNKNOWN"));
        assertThat(settled).isZero();
    }

    @Test
    @DisplayName("여러 인스턴스가 동시에 선점해도 같은 결제를 두 번 고르지 않는다")
    void concurrentClaimsAreDisjoint() throws Exception {
        // given
        Set<Long> payments = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            payments.add(pendingPayment(fixture.createOrder(), now.minusMinutes(2)));
        }
        int instances = 3;
        ExecutorService executor = Executors.newFixedThreadPool(instances);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // when: 각 인스턴스가 주기당 2건씩 동시에 선점한다
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return transactionService.claimInProgress(now, 2);
                }));
            }
            start.countDown();
            List<Long> claimed = new ArrayList<>();
            for (Future<List<Long>> future : futures) {
                claimed.addAll(future.get(30, TimeUnit.SECONDS));
            }

            // then
            List<Long> ours = claimed.stream().filter(payments::contains).toList();
            assertThat(ours).doesNotHaveDuplicates();
        } finally {
            executor.shutdownNow();
        }
    }

    private Long pendingPayment(Order order, OffsetDateTime updatedAt) {
        PaymentPreparation.Ready ready = (PaymentPreparation.Ready) transactionService.prepare(
                fixture.buyerId(), order.getUuid(), IdempotencyKey.from(UUID.randomUUID().toString()),
                RequestHash.from("d".repeat(64)),
                new PaymentRequest("payment-" + order.getId(), PaymentTestFixture.TOTAL_AMOUNT), now.minusMinutes(5));
        setTimes(ready.paymentId(), updatedAt);
        return ready.paymentId();
    }

    private Long unknownPayment(Order order, OffsetDateTime updatedAt) {
        Long paymentId = pendingPayment(order, updatedAt);
        PaymentGatewayResult unknown = PaymentGatewayResult.unknown(null, null, "결제 승인 응답을 확인하지 못했습니다.");
        transactionService.applyResult(paymentId, unknown, unknown, now.minusMinutes(5));
        setTimes(paymentId, updatedAt);
        return paymentId;
    }

    private Long failedPayment(Order order, OffsetDateTime updatedAt) {
        Long paymentId = pendingPayment(order, updatedAt);
        transactionService.applyResult(paymentId,
                PaymentGatewayResult.notApproved("ABORTED", "REJECT_CARD_PAYMENT", "거절"), null, now.minusMinutes(5));
        setTimes(paymentId, updatedAt);
        return paymentId;
    }

    // 생성·변경 시각을 과거로 돌려 정리 대상이 되게 한다. 생성 시각은 10분 경고에 걸리지 않게 변경 시각과 같게 둔다.
    private void setTimes(Long paymentId, OffsetDateTime updatedAt) {
        jdbcTemplate.update("UPDATE payments SET created_at = ?, updated_at = ? WHERE id = ?",
                updatedAt, updatedAt, paymentId);
    }

    private OffsetDateTime updatedAt(Long paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM payments WHERE id = ?", OffsetDateTime.class, paymentId)
                .withOffsetSameInstant(ZoneOffset.UTC);
    }

    private List<String> eventSources(Long paymentId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type || ':' || source FROM payment_events WHERE payment_id = ? ORDER BY id",
                String.class, paymentId);
    }
}
