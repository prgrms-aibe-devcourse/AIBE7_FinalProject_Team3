package org.example.grab.domain.payment.gateway.mock;

import org.example.grab.domain.payment.gateway.PaymentCancelCommand;
import org.example.grab.domain.payment.gateway.PaymentCancelResult;
import org.example.grab.domain.payment.gateway.PaymentConfirmCommand;
import org.example.grab.domain.payment.gateway.PaymentGatewayResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentGatewayTest {

    private static final PaymentConfirmCommand COMMAND =
            new PaymentConfirmCommand("payment-key", "ORD-20260929-000001", 33000, "server-idempotency-key");
    private static final PaymentCancelCommand CANCEL_COMMAND =
            new PaymentCancelCommand("payment-key", "단순 변심", "server-cancel-key");

    @Test
    @DisplayName("기본값(비율 0)이면 confirm()은 항상 승인하고 lookup()도 같은 결과를 돌려준다")
    void confirmApprovedByDefault() {
        // given
        MockPaymentGatewayProperties properties = new MockPaymentGatewayProperties(0, 0, 0, 20);
        MockPaymentGateway gateway = new MockPaymentGateway(properties, Clock.systemUTC(), () -> 0.99);

        // when
        PaymentGatewayResult confirmResult = gateway.confirm(COMMAND);
        PaymentGatewayResult lookupResult = gateway.lookup(COMMAND.paymentKey());

        // then
        assertThat(confirmResult.outcome()).isEqualTo(PaymentGatewayResult.Outcome.APPROVED);
        assertThat(lookupResult).isEqualTo(confirmResult);
    }

    @Test
    @DisplayName("난수가 confirmFailureRate 구간에 들면 승인을 거절한다")
    void confirmRejectedWhenRollInFailureRange() {
        // given
        MockPaymentGatewayProperties properties = new MockPaymentGatewayProperties(0.1, 0.1, 0, 20);
        MockPaymentGateway gateway = new MockPaymentGateway(properties, Clock.systemUTC(), () -> 0.05);

        // when
        PaymentGatewayResult result = gateway.confirm(COMMAND);

        // then
        assertThat(result.outcome()).isEqualTo(PaymentGatewayResult.Outcome.NOT_APPROVED);
    }

    @Test
    @DisplayName("난수가 failureRate와 failureRate+unknownRate 사이에 들면 결과불명으로 떨어진다")
    void confirmUnknownWhenRollInUnknownRange() {
        // given
        MockPaymentGatewayProperties properties = new MockPaymentGatewayProperties(0.1, 0.1, 0, 20);
        MockPaymentGateway gateway = new MockPaymentGateway(properties, Clock.systemUTC(), () -> 0.15);

        // when
        PaymentGatewayResult result = gateway.confirm(COMMAND);

        // then
        assertThat(result.outcome()).isEqualTo(PaymentGatewayResult.Outcome.UNKNOWN);
    }

    @Test
    @DisplayName("난수가 failureRate+unknownRate 이상이면 두 구간을 모두 벗어나 승인된다(구간이 누적으로 이어짐)")
    void confirmApprovedWhenRollAboveBothRanges() {
        // given
        MockPaymentGatewayProperties properties = new MockPaymentGatewayProperties(0.1, 0.1, 0, 20);
        MockPaymentGateway gateway = new MockPaymentGateway(properties, Clock.systemUTC(), () -> 0.25);

        // when
        PaymentGatewayResult result = gateway.confirm(COMMAND);

        // then
        assertThat(result.outcome()).isEqualTo(PaymentGatewayResult.Outcome.APPROVED);
    }

    @Test
    @DisplayName("결과불명 승인은 resolveDelaySeconds가 지나기 전엔 lookup()도 결과불명을 유지하다가, 지나면 확정되고 이후 조회엔 같은 결과를 돌려준다")
    void confirmUnknownResolvesAfterDelay() {
        // given
        MockPaymentGatewayProperties properties = new MockPaymentGatewayProperties(0, 1, 0, 20);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-26T00:00:00Z"));
        MockPaymentGateway gateway = new MockPaymentGateway(properties, clock, () -> 0.5);

        // when: 결과불명으로 confirm()
        PaymentGatewayResult confirmResult = gateway.confirm(COMMAND);
        // then
        assertThat(confirmResult.outcome()).isEqualTo(PaymentGatewayResult.Outcome.UNKNOWN);

        // when: resolveDelaySeconds(20초) 전에 조회
        clock.advance(Duration.ofSeconds(19));
        // then: 여전히 결과불명
        assertThat(gateway.lookup(COMMAND.paymentKey()).outcome()).isEqualTo(PaymentGatewayResult.Outcome.UNKNOWN);

        // when: resolveDelaySeconds가 지난 뒤 조회
        clock.advance(Duration.ofSeconds(1));
        PaymentGatewayResult resolved = gateway.lookup(COMMAND.paymentKey());
        // then: 승인으로 확정
        assertThat(resolved.outcome()).isEqualTo(PaymentGatewayResult.Outcome.APPROVED);
        assertThat(resolved.orderId()).isEqualTo(COMMAND.orderId());
        assertThat(resolved.totalAmount()).isEqualTo(COMMAND.amount());

        // when: 확정 이후 다시 조회
        clock.advance(Duration.ofSeconds(1));
        // then: 같은 결과를 그대로 돌려준다
        assertThat(gateway.lookup(COMMAND.paymentKey())).isEqualTo(resolved);
    }

    @Test
    @DisplayName("기록이 없는 paymentKey를 조회하면 모킹된 결제 기록이 없다는 미승인을 돌려준다")
    void lookupUnknownPaymentKeyReturnsNotFound() {
        // given
        MockPaymentGatewayProperties properties = new MockPaymentGatewayProperties(0, 0, 0, 20);
        MockPaymentGateway gateway = new MockPaymentGateway(properties, Clock.systemUTC(), () -> 0.99);

        // when
        PaymentGatewayResult result = gateway.lookup("never-confirmed");

        // then
        assertThat(result.outcome()).isEqualTo(PaymentGatewayResult.Outcome.NOT_APPROVED);
    }

    @Test
    @DisplayName("비율 0이면 cancel()은 항상 즉시 취소되고, 같은 멱등 키로 재요청해도 최초 결과를 그대로 돌려준다")
    void cancelCanceledByDefaultAndIdempotent() {
        // given
        MockPaymentGatewayProperties properties = new MockPaymentGatewayProperties(0, 0, 0, 20);
        MockPaymentGateway gateway = new MockPaymentGateway(properties, Clock.systemUTC(), () -> 0.99);

        // when
        PaymentCancelResult first = gateway.cancel(CANCEL_COMMAND);
        PaymentCancelResult retry = gateway.cancel(CANCEL_COMMAND);

        // then
        assertThat(first.outcome()).isEqualTo(PaymentCancelResult.Outcome.CANCELED);
        assertThat(retry).isEqualTo(first);
    }

    @Test
    @DisplayName("취소 결과불명은 resolveDelaySeconds 전엔 같은 멱등 키로 재요청해도 결과불명을 유지(재추첨하지 않음)하다가, 지나면 취소로 확정된다")
    void cancelUnknownResolvesAfterDelayWithoutReroll() {
        // given
        MockPaymentGatewayProperties properties = new MockPaymentGatewayProperties(0, 0, 1, 20);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-26T00:00:00Z"));
        Deque<Double> rolls = new ArrayDeque<>();
        rolls.add(0.0); // 최초 호출에서만 결과불명 여부를 굴린다
        rolls.add(0.99); // 재요청에서 또 뽑으면 취소로 바뀌어버리므로, 안 뽑힌다는 걸 확인하려고 반대값을 깔아둔다
        DoubleSupplier queuedRolls = () -> rolls.isEmpty() ? 0.0 : rolls.poll();
        MockPaymentGateway gateway = new MockPaymentGateway(properties, clock, queuedRolls);

        // when: 최초 호출(결과불명 추첨)
        PaymentCancelResult firstCall = gateway.cancel(CANCEL_COMMAND);
        // then
        assertThat(firstCall.outcome()).isEqualTo(PaymentCancelResult.Outcome.UNKNOWN);

        // when: resolveDelaySeconds 전에 같은 키로 재요청
        PaymentCancelResult retryBeforeDelay = gateway.cancel(CANCEL_COMMAND);
        // then: 재추첨 없이 결과불명 유지
        assertThat(retryBeforeDelay.outcome()).isEqualTo(PaymentCancelResult.Outcome.UNKNOWN);

        // when: resolveDelaySeconds가 지난 뒤 재요청
        clock.advance(Duration.ofSeconds(20));
        PaymentCancelResult resolved = gateway.cancel(CANCEL_COMMAND);
        // then: 취소로 확정
        assertThat(resolved.outcome()).isEqualTo(PaymentCancelResult.Outcome.CANCELED);

        // when: 확정 이후 다시 요청
        PaymentCancelResult afterResolved = gateway.cancel(CANCEL_COMMAND);
        // then: 같은 결과를 그대로 돌려준다
        assertThat(afterResolved).isEqualTo(resolved);
    }

    @Test
    @DisplayName("같은 멱등 키로 cancel()을 동시에 호출해도 결과불명 추첨이 단 한 번만 일어나 결과가 갈리지 않는다")
    void cancelDecidesUnknownOnceUnderConcurrency() throws Exception {
        // given
        MockPaymentGatewayProperties properties = new MockPaymentGatewayProperties(0, 0, 0.5, 20);
        AtomicInteger rollCount = new AtomicInteger();
        DoubleSupplier countingRoll = () -> {
            rollCount.incrementAndGet();
            return 0.0; // cancelUnknownRate(0.5)보다 항상 작게 해 결과불명으로 떨어지게 한다
        };
        MockPaymentGateway gateway = new MockPaymentGateway(properties, Clock.systemUTC(), countingRoll);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // when: 같은 멱등 키로 cancel()을 동시에 호출
            List<Future<PaymentCancelResult>> futures = IntStream.range(0, threadCount)
                    .mapToObj(i -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return gateway.cancel(CANCEL_COMMAND);
                    }))
                    .collect(Collectors.toList());
            ready.await();
            start.countDown();

            List<PaymentCancelResult> results = new ArrayList<>();
            for (Future<PaymentCancelResult> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }

            // then: 결과불명 여부 추첨이 키당 한 번만 일어났는지(동시 호출끼리 재추첨하지 않았는지)와,
            // 그 덕에 20개 호출 전부 같은 결과(UNKNOWN)로 수렴했는지를 함께 본다.
            assertThat(rollCount.get()).isEqualTo(1);
            assertThat(results).allSatisfy(result ->
                    assertThat(result.outcome()).isEqualTo(PaymentCancelResult.Outcome.UNKNOWN));
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
