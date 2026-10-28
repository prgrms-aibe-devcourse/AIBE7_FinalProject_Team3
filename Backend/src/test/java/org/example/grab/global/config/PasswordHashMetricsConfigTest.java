package org.example.grab.global.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.security.password.ConcurrencyLimitedPasswordEncoder;
import org.example.grab.global.security.password.PasswordHashProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

// GR-178 M03-01(M00-05): 거부 수·대기 수 지표가 데코레이터 상태를 그대로 보여준다
class PasswordHashMetricsConfigTest {

    private final BlockingEncoder delegate = new BlockingEncoder();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void tearDown() {
        delegate.gate.countDown();
        executor.shutdownNow();
        meters.close();
    }

    private ConcurrencyLimitedPasswordEncoder bind(Duration acquireTimeout) {
        ConcurrencyLimitedPasswordEncoder encoder = new ConcurrencyLimitedPasswordEncoder(delegate,
                new PasswordHashProperties(1, acquireTimeout, Duration.ofSeconds(3)));
        new PasswordHashMetricsConfig().passwordHashMetrics(encoder).bindTo(meters);
        return encoder;
    }

    @Test
    @DisplayName("대기 수 Gauge는 자리를 기다리는 요청 수를 보여주고, 지표에는 태그가 없다")
    void exposesWaitingCount() throws Exception {
        // given: 상한 1을 첫 요청이 쓰고 있다
        ConcurrencyLimitedPasswordEncoder encoder = bind(Duration.ofSeconds(5));
        executor.submit(() -> encoder.encode("raw"));
        delegate.awaitEntered();

        // when: 두 번째 요청이 자리를 기다린다
        executor.submit(() -> encoder.encode("raw"));

        // then
        awaitWaitingGauge(1);
        assertThat(meters.get("grab.password.hash.rejected").functionCounter().count()).isZero();
        assertThat(meters.getMeters()).hasSize(2)
                .allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }

    @Test
    @DisplayName("거부 수 Counter는 대기 시간 안에 자리를 얻지 못한 요청 수를 보여준다")
    void exposesRejectedCount() throws Exception {
        // given: 상한 1을 첫 요청이 쓰고 있고, 대기 시간이 1ms다
        ConcurrencyLimitedPasswordEncoder encoder = bind(Duration.ofMillis(1));
        executor.submit(() -> encoder.encode("raw"));
        delegate.awaitEntered();

        // when
        assertThatThrownBy(() -> encoder.encode("raw")).isInstanceOf(BusinessException.class);

        // then
        assertThat(meters.get("grab.password.hash.rejected").functionCounter().count()).isEqualTo(1);
        assertThat(meters.get("grab.password.hash.waiting").gauge().value()).isZero();
    }

    private void awaitWaitingGauge(double expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (meters.get("grab.password.hash.waiting").gauge().value() != expected) {
            if (System.nanoTime() > deadline) {
                fail("대기 수가 " + expected + "가 되지 않았다");
            }
            Thread.sleep(5);
        }
    }

    // 문이 열릴 때까지 encode 도중에 멈춰, 첫 요청이 자리를 잡고 있는 상태를 만든다
    private static final class BlockingEncoder implements PasswordEncoder {

        private final CountDownLatch gate = new CountDownLatch(1);
        private final CountDownLatch entered = new CountDownLatch(1);

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("첫 해시가 시작되어야 한다").isTrue();
        }

        @Override
        public String encode(CharSequence rawPassword) {
            entered.countDown();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "encoded";
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return false;
        }
    }
}
