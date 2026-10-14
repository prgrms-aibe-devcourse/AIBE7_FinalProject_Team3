package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.domain.payment.dto.OrderCancelRequest;
import org.example.grab.domain.payment.dto.OrderCancelResponse;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.dto.RefundStatus;
import org.example.grab.domain.payment.gateway.PaymentCancelCommand;
import org.example.grab.domain.payment.gateway.PaymentCancelResult;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 결제 취소 재요청 배치(GR-87, ERD.md 3.3)를 실제 DB로 확인한다. 서비스 트랜잭션이 실제로 커밋된다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentCancelRetryIntegrationTest {

    private static final OrderCancelRequest REQUEST = new OrderCancelRequest("단순 변심");
    private static final OffsetDateTime PG_CANCELED_AT = OffsetDateTime.parse("2026-10-06T05:10:00Z");

    @Autowired
    private PaymentBatchService batchService;

    @Autowired
    private OrderCancelService orderCancelService;

    @Autowired
    private OrderCancelTransactionService cancelTransactionService;

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

    // ===== 선점 =====

    @Test
    @DisplayName("재요청 시각이 된 소비자 취소(REQUESTED·UNKNOWN)만 선점하고 next_retry_at을 1분 뒤로 바꿔, 그동안 다시 선점하지 않는다")
    void claimsOnlyDueOrderCancels() {
        // given
        Long paymentId = paidOrder().paymentId();
        Long dueRequested = insertCancellation(paymentId, "ORDER_CANCEL", "REQUESTED", now.minusSeconds(1));
        Long dueUnknown = insertCancellation(paymentId, "ORDER_CANCEL", "UNKNOWN", now.minusSeconds(1));
        Long notDue = insertCancellation(paymentId, "ORDER_CANCEL", "UNKNOWN", now.plusSeconds(30));
        Long compensation = insertCancellation(paymentId, "LATE_APPROVAL_COMPENSATION", "UNKNOWN", now.minusSeconds(1));
        Long failed = insertCancellation(paymentId, "ORDER_CANCEL", "FAILED", null);

        // when
        List<Long> claimed = cancelTransactionService.claimRetries(now, 50);

        // then
        assertThat(claimed).contains(dueRequested, dueUnknown).doesNotContain(notDue, compensation, failed);
        assertThat(nextRetryAt(dueRequested)).isAtSameInstantAs(now.plusMinutes(1));
        assertThat(nextRetryAt(dueUnknown)).isAtSameInstantAs(now.plusMinutes(1));
        assertThat(cancelTransactionService.claimRetries(now.plusSeconds(30), 50))
                .contains(notDue)
                .doesNotContain(dueRequested, dueUnknown);
        assertThat(cancelTransactionService.claimRetries(now.plusMinutes(1), 50)).contains(dueRequested, dueUnknown);
    }

    @Test
    @DisplayName("여러 인스턴스가 동시에 선점해도 같은 취소를 두 번 고르지 않는다")
    void concurrentClaimsAreDisjoint() throws Exception {
        // given
        Long paymentId = paidOrder().paymentId();
        Set<Long> cancellations = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            cancellations.add(insertCancellation(paymentId, "ORDER_CANCEL", "UNKNOWN", now.minusSeconds(1)));
        }

        // when: 각 인스턴스가 주기당 2건씩 동시에 선점한다
        List<List<Long>> results = runConcurrently(List.of(
                () -> cancelTransactionService.claimRetries(now, 2),
                () -> cancelTransactionService.claimRetries(now, 2),
                () -> cancelTransactionService.claimRetries(now, 2)));

        // then
        List<Long> ours = results.stream().flatMap(List::stream).filter(cancellations::contains).toList();
        assertThat(ours).doesNotHaveDuplicates();
    }

    // ===== 재요청과 반영 =====

    @Test
    @DisplayName("PG 호출 전에 멈춰 REQUESTED로 남은 취소를 1분 뒤 같은 서버 멱등 키로 요청해 주문 취소와 재고 반환까지 확정한다")
    void retriesRequestedCancelLeftBeforePgCall() {
        // given: 취소 요청을 기록한 뒤 PG 호출에서 서버가 멈췄다
        PaidOrder paid = paidOrder();
        given(paymentGateway.cancel(any()))
                .willThrow(new IllegalStateException("서버 중단"))
                .willReturn(canceled());
        String key = newKey();
        assertThatThrownBy(() -> cancel(paid.order(), key)).isInstanceOf(IllegalStateException.class);
        Map<String, Object> requested = cancellationRow(paid.paymentId());
        assertThat(requested).containsEntry("status", "REQUESTED").containsEntry("attempt_count", 0);
        // 생성할 때 next_retry_at = now + 1분이라 사용자 요청과 겹치는 동안에는 대상이 아니다
        assertThat(cancelTransactionService.claimRetries(now, 50)).doesNotContain(cancellationId(paid.paymentId()));

        // when
        int decided = batchService.retryCancellations(now.plusMinutes(2));

        // then
        assertThat(decided).isGreaterThanOrEqualTo(1);
        assertThat(cancellationRow(paid.paymentId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("attempt_count", 1)
                .containsEntry("next_retry_at", null);
        assertThat(fixture.orderStatus(paid.order())).isEqualTo(OrderStatus.CANCELED);
        assertThat(paymentStatus(paid.paymentId())).isEqualTo("CANCELED");
        assertThat(fixture.reservationStatus(paid.order())).isEqualTo("RELEASED");
        assertThat(fixture.quantities()).containsEntry("reserved_quantity", 0).containsEntry("sold_quantity", 0);
        List<PaymentCancelCommand> commands = capturedCommands(2);
        assertThat(commands.get(1).idempotencyKey())
                .isEqualTo(commands.get(0).idempotencyKey())
                .isEqualTo(requested.get("idempotency_key"));

        // 같은 키로 재전송하면 PG를 다시 부르지 않고 확정된 결과를 받는다
        OrderCancelResponse replay = cancel(paid.order(), key);
        assertThat(replay.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(replay.refundStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        verify(paymentGateway, times(2)).cancel(any());
    }

    @Test
    @DisplayName("결과를 모르는 취소를 배치가 다시 요청해도 결과를 모르면 UNKNOWN으로 남기고 1분 뒤 다시 대상이 된다")
    void keepsUnknownCancelForNextRetry() {
        // given
        PaidOrder paid = paidOrder();
        given(paymentGateway.cancel(any()))
                .willReturn(PaymentCancelResult.unknown(null, null, "결제 취소 응답을 확인하지 못했습니다."));
        cancel(paid.order(), newKey());
        OffsetDateTime retriedFrom = OffsetDateTime.now(ZoneOffset.UTC);

        // when
        batchService.retryCancellations(now.plusMinutes(2));

        // then: 결과를 반영한 시각부터 1분 뒤에 다시 대상이 된다
        Map<String, Object> cancellation = cancellationRow(paid.paymentId());
        assertThat(cancellation).containsEntry("status", "UNKNOWN").containsEntry("attempt_count", 2);
        assertThat(nextRetryAt(cancellationId(paid.paymentId()))).isAfterOrEqualTo(retriedFrom.plusMinutes(1));
        assertThat(fixture.orderStatus(paid.order())).isEqualTo(OrderStatus.PAID);
        assertThat(fixture.quantities()).containsEntry("sold_quantity", PaymentTestFixture.QUANTITY);
    }

    @Test
    @DisplayName("배치의 재요청을 PG가 거절하면 취소를 FAILED로 확정하고 주문의 취소 요청을 풀어, 새 키로 다시 취소할 수 있다")
    void releasesCancelRequestWhenRetryRejected() {
        // given
        PaidOrder paid = paidOrder();
        given(paymentGateway.cancel(any()))
                .willReturn(PaymentCancelResult.unknown(null, null, "결제 취소 응답을 확인하지 못했습니다."))
                .willReturn(PaymentCancelResult.rejected("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다."))
                .willReturn(canceled());
        cancel(paid.order(), newKey());

        // when
        batchService.retryCancellations(now.plusMinutes(2));

        // then
        assertThat(cancellationRow(paid.paymentId()))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_code", "NOT_CANCELABLE_PAYMENT")
                .containsEntry("next_retry_at", null);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, cancel_idempotency_key FROM orders WHERE id = ?", paid.order().getId()))
                .containsEntry("status", "PAID")
                .containsEntry("cancel_idempotency_key", null);
        assertThat(paymentStatus(paid.paymentId())).isEqualTo("SUCCEEDED");
        assertThat(fixture.quantities()).containsEntry("sold_quantity", PaymentTestFixture.QUANTITY);
        assertThat(cancel(paid.order(), newKey()).status()).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    @DisplayName("배치와 같은 키의 사용자 재전송이 동시에 같은 취소를 요청해도 결과는 한 번만 반영되고 재고는 한 번만 돌아온다")
    void retryAndResendApplyOnce() throws Exception {
        // given
        PaidOrder paid = paidOrder();
        given(paymentGateway.cancel(any()))
                .willReturn(PaymentCancelResult.unknown(null, null, "결제 취소 응답을 확인하지 못했습니다."))
                .willReturn(canceled());
        String key = newKey();
        cancel(paid.order(), key);

        // when
        List<Object> results = runConcurrently(List.of(
                () -> batchService.retryCancellations(now.plusMinutes(2)),
                () -> cancel(paid.order(), key)));

        // then
        OrderCancelResponse resend = (OrderCancelResponse) results.get(1);
        assertThat(resend.refundStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(resend.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(cancellationRow(paid.paymentId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("attempt_count", 2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_cancellations WHERE payment_id = ?", Integer.class, paid.paymentId()))
                .isEqualTo(1);
        assertThat(fixture.orderStatus(paid.order())).isEqualTo(OrderStatus.CANCELED);
        assertThat(fixture.quantities()).containsEntry("reserved_quantity", 0).containsEntry("sold_quantity", 0);
        // 배치가 선점한 뒤 재전송이 먼저 확정했으면 배치는 PG를 부르지 않으므로 2~3번이다. 모두 같은 서버 멱등 키다.
        ArgumentCaptor<PaymentCancelCommand> captor = ArgumentCaptor.forClass(PaymentCancelCommand.class);
        verify(paymentGateway, atLeast(2)).cancel(captor.capture());
        String serverKey = (String) cancellationRow(paid.paymentId()).get("idempotency_key");
        assertThat(captor.getAllValues()).extracting(PaymentCancelCommand::idempotencyKey).containsOnly(serverKey);
    }

    private OrderCancelResponse cancel(Order order, String key) {
        return orderCancelService.cancel(fixture.buyerId(), order.getUuid(), key, REQUEST);
    }

    // 결제 승인까지 끝나 주문이 PAID, 예약이 COMMITTED, 선점 수량이 판매 수량으로 옮겨진 상태를 만든다.
    private PaidOrder paidOrder() {
        Order order = fixture.createOrder();
        String paymentKey = "payment-" + UUID.randomUUID();
        PaymentPreparation.Ready ready = (PaymentPreparation.Ready) transactionService.prepare(
                fixture.buyerId(), order.getUuid(), IdempotencyKey.from(newKey()), RequestHash.from("c".repeat(64)),
                new PaymentRequest(paymentKey, PaymentTestFixture.TOTAL_AMOUNT), now);
        transactionService.applyResult(ready.paymentId(), PaymentGatewayResult.approved(
                "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT, now), null, now);
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAID);
        return new PaidOrder(order, ready.paymentId());
    }

    // 선점 조건만 확인하는 테스트용 취소 기록. 주문의 취소 요청은 기록하지 않는다.
    private Long insertCancellation(Long paymentId, String purpose, String status, OffsetDateTime nextRetryAt) {
        boolean completed = status.equals("SUCCEEDED") || status.equals("FAILED");
        return jdbcTemplate.queryForObject("""
                INSERT INTO payment_cancellations
                    (payment_id, purpose, idempotency_key, amount, reason, status, next_retry_at, completed_at)
                VALUES (?, ?, ?, ?, '단순 변심', ?, ?, ?)
                RETURNING id
                """, Long.class, paymentId, purpose, UUID.randomUUID().toString(), PaymentTestFixture.TOTAL_AMOUNT,
                status, nextRetryAt, completed ? now : null);
    }

    private <T> List<T> runConcurrently(List<Callable<? extends T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<? extends T>> futures = new ArrayList<>();
            for (Callable<? extends T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<? extends T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static PaymentCancelResult canceled() {
        return PaymentCancelResult.canceled("CANCELED", "cancel-transaction-key", PG_CANCELED_AT);
    }

    private List<PaymentCancelCommand> capturedCommands(int count) {
        ArgumentCaptor<PaymentCancelCommand> captor = ArgumentCaptor.forClass(PaymentCancelCommand.class);
        verify(paymentGateway, times(count)).cancel(captor.capture());
        return captor.getAllValues();
    }

    private Map<String, Object> cancellationRow(Long paymentId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, attempt_count, idempotency_key, failure_code, next_retry_at
                FROM payment_cancellations WHERE payment_id = ? ORDER BY id LIMIT 1
                """, paymentId);
    }

    private Long cancellationId(Long paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM payment_cancellations WHERE payment_id = ? ORDER BY id LIMIT 1", Long.class, paymentId);
    }

    private OffsetDateTime nextRetryAt(Long cancellationId) {
        return jdbcTemplate.queryForObject(
                "SELECT next_retry_at FROM payment_cancellations WHERE id = ?", OffsetDateTime.class, cancellationId);
    }

    private String paymentStatus(Long paymentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private record PaidOrder(Order order, Long paymentId) {
    }
}
