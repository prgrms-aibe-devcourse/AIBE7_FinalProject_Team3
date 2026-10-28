package org.example.grab.domain.drop.stream.service;

import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.stream.StockStreamTestSupport.MutableClock;
import org.example.grab.domain.drop.stream.model.StockStreamSubscription;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.grab.domain.drop.stream.StockStreamTestSupport.defaults;
import static org.example.grab.domain.drop.stream.StockStreamTestSupport.properties;

class StockStreamRegistryTest {

    private final MutableClock clock = new MutableClock();

    @Test
    @DisplayName("상품별 구독을 분리하고 중복 해제에도 슬롯·인덱스가 일치한다")
    void separatesSubscriptionsAndReleasesIdempotently() {
        // given
        StockStreamRegistry registry = new StockStreamRegistry(properties(2, 2, 30, 10), clock);
        StockStreamSubscription first = registry.register("a", List.of(1L, 2L));
        StockStreamSubscription second = registry.register("b", List.of(2L, 3L));

        // when & then
        assertThat(registry.findSubscribers(1)).containsExactly(first);
        assertThat(registry.findSubscribers(2)).containsExactlyInAnyOrder(first, second);
        assertThat(registry.findSubscribers(3)).containsExactly(second);
        assertThat(registry.unregister(first.id())).isTrue();
        assertThat(registry.unregister(first.id())).isFalse();
        assertThat(registry.unregister(UUID.randomUUID())).isFalse();
        assertThat(registry.findSubscribers(1)).isEmpty();
        assertThat(registry.findSubscribers(2)).containsExactly(second);
        assertThat(registry.countConnections()).isEqualTo(1);
        assertThat(registry.register("a", List.of(4L))).isNotNull();
    }

    @Test
    @DisplayName("같은 IP 51번째 연결은 429이고 다른 IP의 서버 1001번째 연결은 503이다")
    void enforcesDefaultConnectionBoundaries() {
        // given
        StockStreamRegistry registry = new StockStreamRegistry(defaults(), clock);
        for (int i = 0; i < 50; i++) {
            registry.register("same", List.of(1L));
        }

        // when & then
        assertError(() -> registry.register("same", List.of(1L)), DropErrorCode.STOCK_STREAM_LIMIT_EXCEEDED);
        for (int i = 0; i < 950; i++) {
            registry.register("other-" + i, List.of(1L));
        }
        assertError(() -> registry.register("last", List.of(1L)), DropErrorCode.STOCK_STREAM_UNAVAILABLE);
        assertThat(registry.countConnections()).isEqualTo(1000);
        assertThat(registry.findSubscribers(1)).hasSize(1000);
    }

    @Test
    @DisplayName("첫 요청부터 60초 고정 창으로 76번째를 거부하고 만료 경계에서 새 창을 연다")
    void expiresFixedRequestWindow() {
        // given
        StockStreamRegistry registry = new StockStreamRegistry(defaults(), clock);
        StockStreamSubscription active = registry.register("a", List.of(1L));
        for (int i = 0; i < 75; i++) {
            registry.recordAttempt("a");
        }

        // when & then
        clock.advance(Duration.ofSeconds(59));
        assertError(() -> registry.recordAttempt("a"), DropErrorCode.STOCK_STREAM_LIMIT_EXCEEDED);
        clock.advance(Duration.ofSeconds(1));
        registry.recordAttempt("a");
        assertThat(registry.findSubscribers(1)).containsExactly(active);
        assertThat(registry.countConnections()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료 IP를 정리하되 활성 연결 IP는 유지하고 추적 용량 초과는 503으로 거부한다")
    void boundsIpTrackingAndCleansExpiredState() {
        // given
        StockStreamRegistry registry = new StockStreamRegistry(properties(10, 10, 30, 2), clock);
        StockStreamSubscription active = registry.register("active", List.of(1L));
        registry.recordAttempt("failed");
        assertError(() -> registry.recordAttempt("third"), DropErrorCode.STOCK_STREAM_UNAVAILABLE);

        // when
        clock.advance(Duration.ofSeconds(60));
        registry.removeExpiredIps();
        registry.recordAttempt("third");

        // then
        assertThat(registry.countTrackedIps()).isEqualTo(2);
        assertThat(registry.findSubscribers(1)).containsExactly(active);
        registry.unregister(active.id());
        assertThat(registry.countTrackedIps()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(60));
        assertThat(registry.countTrackedIps()).isZero();
    }

    @Test
    @DisplayName("등록 인수 오류는 연결 슬롯·IP 상태·상품 인덱스를 만들지 않는다")
    void rejectsInvalidRegistrationWithoutResources() {
        // given
        StockStreamRegistry registry = new StockStreamRegistry(defaults(), clock);

        // when & then
        assertThatThrownBy(() -> registry.register("a", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register("a", List.of(1L, 1L))).isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.countConnections()).isZero();
        assertThat(registry.countTrackedIps()).isZero();
    }

    @Test
    @DisplayName("동시 등록에서도 전체 연결 상한을 초과하지 않는다")
    void enforcesTotalLimitConcurrently() throws Exception {
        verifyConcurrentAdmission(false);
    }

    @Test
    @DisplayName("동일 IP 동시 등록에서도 IP 상한을 초과하지 않는다")
    void enforcesIpLimitConcurrently() throws Exception {
        verifyConcurrentAdmission(true);
    }

    private void verifyConcurrentAdmission(boolean sameIp) throws Exception {
        // given
        StockStreamRegistry registry = new StockStreamRegistry(properties(20, 10, 100, 100), clock);
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 64; i++) {
                String ip = sameIp ? "same" : "ip-" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        registry.register(ip, List.of(1L, 2L));
                        return 1;
                    } catch (BusinessException error) {
                        assertThat(error.getErrorCode()).isEqualTo(sameIp
                                ? DropErrorCode.STOCK_STREAM_LIMIT_EXCEEDED : DropErrorCode.STOCK_STREAM_UNAVAILABLE);
                        return 0;
                    }
                }));
            }
            // when
            start.countDown();
            int accepted = 0;
            for (Future<Integer> result : results) {
                accepted += result.get(10, TimeUnit.SECONDS);
            }

            // then
            assertThat(accepted).isEqualTo(sameIp ? 10 : 20);
            assertThat(registry.countConnections()).isEqualTo(accepted);
            assertThat(registry.findSubscribers(1)).hasSize(accepted);
            assertThat(registry.findSubscribers(2)).hasSize(accepted);
            List<StockStreamSubscription> active = registry.findSubscribers(1);
            List<Future<Boolean>> removals = new ArrayList<>();
            for (StockStreamSubscription subscription : active) {
                removals.add(executor.submit(() -> registry.unregister(subscription.id())));
                removals.add(executor.submit(() -> registry.unregister(subscription.id())));
            }
            int removed = 0;
            for (Future<Boolean> removal : removals) {
                removed += removal.get(10, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(removed).isEqualTo(accepted);
            assertThat(registry.countConnections()).isZero();
            assertThat(registry.findSubscribers(1)).isEmpty();
            assertThat(registry.findSubscribers(2)).isEmpty();
            assertThat(registry.register("same", List.of(1L))).isNotNull();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void assertError(Runnable action, DropErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
