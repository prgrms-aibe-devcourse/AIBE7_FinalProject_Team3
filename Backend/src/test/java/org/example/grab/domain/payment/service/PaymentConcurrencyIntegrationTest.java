package org.example.grab.domain.payment.service;

import org.example.grab.domain.order.dto.PaymentExpiryResult;
import org.example.grab.domain.order.entity.Order;
import org.example.grab.domain.order.entity.OrderStatus;
import org.example.grab.domain.order.repository.OrderItemRepository;
import org.example.grab.domain.order.repository.OrderRepository;
import org.example.grab.domain.order.repository.StockReservationRepository;
import org.example.grab.domain.order.service.OrderPaymentService;
import org.example.grab.domain.payment.dto.PaymentRequest;
import org.example.grab.domain.payment.dto.PaymentResponse;
import org.example.grab.domain.payment.entity.PaymentStatus;
import org.example.grab.domain.payment.error.PaymentErrorCode;
import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.example.grab.global.error.BusinessException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 결제 확정과 만료, 같은 주문의 동시 결제 요청이 실제 PostgreSQL 행 잠금 아래에서 한 경로만 성공하는지 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentConcurrencyIntegrationTest {

    @Autowired
    private PaymentService paymentService;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private PaymentTestFixture fixture;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        fixture = new PaymentTestFixture(jdbcTemplate, orderRepository, orderItemRepository, stockReservationRepository);
        executor = Executors.newFixedThreadPool(3);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        fixture.cleanup();
    }

    @Test
    @DisplayName("결제 승인 반영이 먼저 주문을 잠그면 결제가 확정되고, 기다리던 만료는 아무것도 바꾸지 않는다")
    void approvalWinsOverExpiry() throws Exception {
        // given
        Race race = prepareRace();

        // when: 승인 반영이 먼저 잠금을 기다리고, 그 뒤에 만료가 기다린다
        RaceResult result = race.run(true);

        // then
        assertThat(result.expired()).isFalse();
        assertThat(fixture.orderStatus(race.order())).isEqualTo(OrderStatus.PAID);
        assertThat(fixture.reservationStatus(race.order())).isEqualTo("COMMITTED");
        assertThat(fixture.payments(race.order())).singleElement().satisfies(payment -> {
            assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
            assertThat(payment.get("reconciliation_status")).isEqualTo("NONE");
        });
        assertThat(fixture.quantities())
                .containsEntry("reserved_quantity", 0)
                .containsEntry("sold_quantity", PaymentTestFixture.QUANTITY);
    }

    @Test
    @DisplayName("만료가 먼저 주문을 잠그면 재고가 반환되고, 기다리던 승인 반영은 주문을 확정하지 않고 보정 대상으로 남긴다")
    void expiryWinsOverApproval() throws Exception {
        // given
        Race race = prepareRace();

        // when: 만료가 먼저 잠금을 기다리고, 그 뒤에 승인 반영이 기다린다
        RaceResult result = race.run(false);

        // then
        assertThat(result.expired()).isTrue();
        assertThat(fixture.orderStatus(race.order())).isEqualTo(OrderStatus.EXPIRED);
        assertThat(fixture.reservationStatus(race.order())).isEqualTo("RELEASED");
        // PG는 승인했지만 주문이 이미 만료됐으므로 환불 등 보정 대상으로 남는다(PAY-004).
        assertThat(fixture.payments(race.order())).singleElement().satisfies(payment -> {
            assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
            assertThat(payment.get("reconciliation_status")).isEqualTo("REQUIRED");
        });
        assertThat(fixture.quantities())
                .containsEntry("reserved_quantity", 0)
                .containsEntry("sold_quantity", 0);
    }

    @Test
    @DisplayName("같은 결제에 결과 반영이 겹치면 먼저 반영한 결과를 유지하고, 늦게 들어온 반영은 아무것도 바꾸지 않는다")
    void concurrentResultsForSamePayment() throws Exception {
        // given: 두 반영 요청이 모두 결제를 읽은 뒤 주문 잠금을 기다린다
        Race race = prepareRace();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int baseline = waitingForLock();
        Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    jdbcTemplate.queryForObject("SELECT id FROM orders WHERE id = ? FOR UPDATE", Long.class,
                            race.order().getId());
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // when
        Future<?> first = executor.submit(
                () -> transactionService.applyResult(race.paymentId, race.approved, null, race.beforeDeadline));
        awaitWaitingForLock(baseline + 1);
        Future<?> second = executor.submit(
                () -> transactionService.applyResult(race.paymentId, race.approved, null, race.beforeDeadline));
        awaitWaitingForLock(baseline + 2);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        // then: 늦은 반영이 확정된 결제를 보정 대상으로 덮어쓰지 않는다
        assertThat(fixture.orderStatus(race.order())).isEqualTo(OrderStatus.PAID);
        assertThat(fixture.payments(race.order())).singleElement().satisfies(payment -> {
            assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
            assertThat(payment.get("reconciliation_status")).isEqualTo("NONE");
        });
        assertThat(fixture.quantities())
                .containsEntry("reserved_quantity", 0)
                .containsEntry("sold_quantity", PaymentTestFixture.QUANTITY);
    }

    @Test
    @DisplayName("결제 승인 반영이 주문을 잠그고 있으면 만료 배치는 기다리지 않고 건너뛰고, 확정된 주문은 이후에도 만료하지 않는다")
    void expiryBatchSkipsOrderLockedByApproval() throws Exception {
        // given: 승인 반영 트랜잭션이 주문을 잠그고 커밋 전에 멈춘다
        Race race = prepareRace();
        OffsetDateTime deadline = race.order().getPaymentExpiresAt();
        CountDownLatch applied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> approval = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    transactionService.applyResult(race.paymentId, race.approved, null, race.beforeDeadline);
                    applied.countDown();
                    await(release);
                }));
        assertThat(applied.await(10, TimeUnit.SECONDS)).isTrue();

        // when
        PaymentExpiryResult whileLocked = executor.submit(
                () -> orderPaymentService.expireIfDueSkippingLocked(race.order().getId(), deadline))
                .get(5, TimeUnit.SECONDS);
        release.countDown();
        approval.get(10, TimeUnit.SECONDS);
        PaymentExpiryResult afterApproval = orderPaymentService.expireIfDueSkippingLocked(race.order().getId(), deadline);

        // then
        assertThat(whileLocked).isEqualTo(PaymentExpiryResult.SKIPPED_LOCKED);
        assertThat(afterApproval).isEqualTo(PaymentExpiryResult.NOT_DUE);
        assertThat(fixture.orderStatus(race.order())).isEqualTo(OrderStatus.PAID);
        assertThat(fixture.payments(race.order())).singleElement().satisfies(payment -> {
            assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
            assertThat(payment.get("reconciliation_status")).isEqualTo("NONE");
        });
        assertThat(fixture.quantities())
                .containsEntry("reserved_quantity", 0)
                .containsEntry("sold_quantity", PaymentTestFixture.QUANTITY);
    }

    @Test
    @DisplayName("만료 배치가 먼저 만료하면 이후 승인 반영은 주문을 확정하지 않고 보정 대상으로 남긴다")
    void approvalAfterExpiryBatchRequiresReconciliation() {
        // given
        Race race = prepareRace();

        // when
        PaymentExpiryResult expiry = orderPaymentService.expireIfDueSkippingLocked(
                race.order().getId(), race.order().getPaymentExpiresAt());
        transactionService.applyResult(race.paymentId, race.approved, null, race.order().getPaymentExpiresAt());

        // then
        assertThat(expiry).isEqualTo(PaymentExpiryResult.EXPIRED);
        assertThat(fixture.orderStatus(race.order())).isEqualTo(OrderStatus.EXPIRED);
        assertThat(fixture.reservationStatus(race.order())).isEqualTo("RELEASED");
        assertThat(fixture.payments(race.order())).singleElement().satisfies(payment -> {
            assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
            assertThat(payment.get("reconciliation_status")).isEqualTo("REQUIRED");
        });
        assertThat(fixture.quantities())
                .containsEntry("reserved_quantity", 0)
                .containsEntry("sold_quantity", 0);
    }

    @Test
    @DisplayName("여러 인스턴스의 만료 배치가 같은 주문을 동시에 처리해도 한 번만 만료하고 재고도 한 번만 반환한다")
    void concurrentExpiryBatchesExpireOnce() throws Exception {
        // given
        Order order = fixture.createOrder();
        OffsetDateTime deadline = order.getPaymentExpiresAt();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentExpiryResult>> results = new ArrayList<>();

        // when
        for (int i = 0; i < 3; i++) {
            results.add(executor.submit(() -> {
                await(start);
                return orderPaymentService.expireIfDueSkippingLocked(order.getId(), deadline);
            }));
        }
        start.countDown();
        List<PaymentExpiryResult> outcomes = new ArrayList<>();
        for (Future<PaymentExpiryResult> result : results) {
            outcomes.add(result.get(10, TimeUnit.SECONDS));
        }

        // then: 나머지는 잠긴 주문을 건너뛰거나, 이미 만료된 주문을 대상 아님으로 본다
        assertThat(outcomes).filteredOn(PaymentExpiryResult.EXPIRED::equals).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome != PaymentExpiryResult.EXPIRED)
                .allMatch(outcome -> outcome == PaymentExpiryResult.SKIPPED_LOCKED
                        || outcome == PaymentExpiryResult.NOT_DUE);
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(fixture.reservationStatus(order)).isEqualTo("RELEASED");
        assertThat(fixture.quantities()).containsEntry("reserved_quantity", 0);
    }

    @Test
    @DisplayName("다른 트랜잭션이 주문을 잠그고 있으면 만료 배치는 건너뛰고, 잠금이 풀린 뒤 다음 실행에서 만료한다")
    void expiryBatchRetriesSkippedOrderOnNextRun() throws Exception {
        // given
        Order order = fixture.createOrder();
        OffsetDateTime deadline = order.getPaymentExpiresAt();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    jdbcTemplate.queryForObject("SELECT id FROM orders WHERE id = ? FOR UPDATE", Long.class,
                            order.getId());
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // when
        PaymentExpiryResult whileLocked = executor.submit(
                () -> orderPaymentService.expireIfDueSkippingLocked(order.getId(), deadline))
                .get(5, TimeUnit.SECONDS);
        OrderStatus statusWhileLocked = fixture.orderStatus(order);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        PaymentExpiryResult nextRun = orderPaymentService.expireIfDueSkippingLocked(order.getId(), deadline);

        // then
        assertThat(whileLocked).isEqualTo(PaymentExpiryResult.SKIPPED_LOCKED);
        assertThat(statusWhileLocked).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(nextRun).isEqualTo(PaymentExpiryResult.EXPIRED);
        assertThat(fixture.quantities()).containsEntry("reserved_quantity", 0);
    }

    // 결제 시도가 PENDING으로 저장되고 PG 승인이 끝난 직후, 결제 마감에 도달한 상황을 만든다.
    private Race prepareRace() {
        Order order = fixture.createOrder();
        OffsetDateTime beforeDeadline = order.getPaymentExpiresAt().minusSeconds(1);
        PaymentPreparation.Ready ready = (PaymentPreparation.Ready) transactionService.prepare(
                fixture.buyerId(), order.getUuid(), IdempotencyKey.from(UUID.randomUUID().toString()),
                RequestHash.from("c".repeat(64)), new PaymentRequest("payment-race", PaymentTestFixture.TOTAL_AMOUNT),
                beforeDeadline);
        PaymentGatewayResult approved = PaymentGatewayResult.approved(
                "DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT, beforeDeadline);
        return new Race(order, ready.paymentId(), approved, beforeDeadline);
    }

    private record RaceResult(boolean expired) {
    }

    /*
        테스트가 주문 행을 먼저 잠근 채 두 경로를 정한 순서대로 출발시키고, 둘 다 잠금 대기에 들어간 것을 확인한 뒤 잠금을 푼다.
        PostgreSQL은 기다린 순서대로 행 잠금을 넘겨주므로, 두 경로가 실제로 경합하는 상태에서 먼저 잠근 쪽을 정할 수 있다.
     */
    private class Race {

        private final Order order;
        private final Long paymentId;
        private final PaymentGatewayResult approved;
        private final OffsetDateTime beforeDeadline;

        Race(Order order, Long paymentId, PaymentGatewayResult approved, OffsetDateTime beforeDeadline) {
            this.order = order;
            this.paymentId = paymentId;
            this.approved = approved;
            this.beforeDeadline = beforeDeadline;
        }

        Order order() {
            return order;
        }

        RaceResult run(boolean approvalFirst) throws Exception {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            int baseline = waitingForLock();
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> {
                        jdbcTemplate.queryForObject("SELECT id FROM orders WHERE id = ? FOR UPDATE", Long.class,
                                order.getId());
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> approval;
            Future<Boolean> expiry;
            if (approvalFirst) {
                approval = executor.submit(() -> transactionService.applyResult(paymentId, approved, null, beforeDeadline));
                awaitWaitingForLock(baseline + 1);
                expiry = executor.submit(() -> orderPaymentService.expireIfDue(order.getId(), order.getPaymentExpiresAt()));
                awaitWaitingForLock(baseline + 2);
            } else {
                expiry = executor.submit(() -> orderPaymentService.expireIfDue(order.getId(), order.getPaymentExpiresAt()));
                awaitWaitingForLock(baseline + 1);
                approval = executor.submit(() -> transactionService.applyResult(paymentId, approved, null, beforeDeadline));
                awaitWaitingForLock(baseline + 2);
            }

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            approval.get(10, TimeUnit.SECONDS);
            return new RaceResult(expiry.get(10, TimeUnit.SECONDS));
        }
    }

    private int waitingForLock() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                Integer.class);
        return count == null ? 0 : count;
    }

    private void awaitWaitingForLock(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (waitingForLock() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("행 잠금 대기에 들어간 트랜잭션이 " + expected + "개가 되지 않았습니다.");
            }
            Thread.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 신호를 받지 못했습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("같은 주문에 다른 멱등 키로 결제 요청이 겹치면 먼저 들어온 결제만 진행하고 나머지는 거부한다")
    void concurrentPaymentRequestsForSameOrder() throws Exception {
        // given: 첫 요청이 PG 승인 응답을 기다리는 동안 두 번째 요청이 들어온다
        Order order = fixture.createOrder();
        CountDownLatch confirmStarted = new CountDownLatch(1);
        CountDownLatch releaseConfirm = new CountDownLatch(1);
        given(paymentGateway.confirm(any())).willAnswer(invocation -> {
            confirmStarted.countDown();
            releaseConfirm.await(10, TimeUnit.SECONDS);
            return PaymentGatewayResult.approved("DONE", order.getOrderNumber(), PaymentTestFixture.TOTAL_AMOUNT,
                    OffsetDateTime.now(ZoneOffset.UTC));
        });

        // when
        Future<PaymentResponse> first = executor.submit(() -> paymentService.pay(
                fixture.buyerId(), order.getUuid(), UUID.randomUUID().toString(),
                new PaymentRequest("payment-first", PaymentTestFixture.TOTAL_AMOUNT)));
        assertThat(confirmStarted.await(10, TimeUnit.SECONDS)).isTrue();

        // then: 두 번째 요청은 진행 중인 결제 때문에 PG를 부르지 않고 거부된다
        assertThatThrownBy(() -> paymentService.pay(
                fixture.buyerId(), order.getUuid(), UUID.randomUUID().toString(),
                new PaymentRequest("payment-second", PaymentTestFixture.TOTAL_AMOUNT)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);

        releaseConfirm.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(fixture.payments(order)).hasSize(1);
        assertThat(fixture.orderStatus(order)).isEqualTo(OrderStatus.PAID);
        assertThat(fixture.quantities())
                .containsEntry("reserved_quantity", 0)
                .containsEntry("sold_quantity", PaymentTestFixture.QUANTITY);
    }

    @Test
    @DisplayName("다른 주문이 같은 paymentKey로 동시에 결제 요청하면 늦은 요청은 PG를 부르지 않고 409로 거부한다")
    void concurrentPaymentRequestsWithSamePaymentKey() throws Exception {
        // given: 주문 A의 결제 시도가 paymentKey를 저장하고 아직 커밋하지 않았다
        Order orderA = fixture.createOrder();
        Order orderB = fixture.createOrder();
        String paymentKey = "payment-shared";
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int baseline = waitingForLock();
        Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    transactionService.prepare(
                            fixture.buyerId(), orderA.getUuid(), IdempotencyKey.from(UUID.randomUUID().toString()),
                            RequestHash.from("d".repeat(64)),
                            new PaymentRequest(paymentKey, PaymentTestFixture.TOTAL_AMOUNT),
                            OffsetDateTime.now(ZoneOffset.UTC));
                    saved.countDown();
                    await(release);
                }));
        assertThat(saved.await(10, TimeUnit.SECONDS)).isTrue();

        // when: 주문 B의 요청은 주문 잠금이 달라 사전 확인을 통과하고, 저장에서 유니크 인덱스를 기다린다
        Future<PaymentResponse> late = executor.submit(() -> paymentService.pay(
                fixture.buyerId(), orderB.getUuid(), UUID.randomUUID().toString(),
                new PaymentRequest(paymentKey, PaymentTestFixture.TOTAL_AMOUNT)));
        awaitWaitingForLock(baseline + 1);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        // then
        assertThatThrownBy(() -> late.get(10, TimeUnit.SECONDS))
                .cause()
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
        verify(paymentGateway, never()).confirm(any());
        assertThat(fixture.payments(orderB)).isEmpty();
        assertThat(fixture.orderStatus(orderB)).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }
}
