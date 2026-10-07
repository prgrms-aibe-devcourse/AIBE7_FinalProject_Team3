package org.example.grab.domain.user.repository;

import org.example.grab.domain.user.support.EmailVerificationConfig;
import org.example.grab.domain.user.support.EmailVerificationHasher;
import org.example.grab.domain.user.support.EmailVerificationSendLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/*
    GR-61 M02-02: 실제 Redis(Testcontainers)에서 발송 제한 저장소를 확인한다.
    한도 값은 application.yml의 send-limit 설정(60초·24시간·1시간)을 그대로 바인딩해 쓴다.
 */
@DataRedisTest
@Import({EmailVerificationSendLimitRepository.class, EmailVerificationHasher.class, EmailVerificationConfig.class})
@Testcontainers
class EmailVerificationSendLimitRepositoryIntegrationTest {

    private static final String EMAIL = "user@example.com";
    private static final String OTHER_EMAIL = "other@example.com";
    private static final String IP = "203.0.113.7";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 컨테이너 Redis는 인증이 없다. 빈 비밀번호는 "비밀번호 없음"으로 처리된다
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private EmailVerificationSendLimitRepository repository;

    @Autowired
    private EmailVerificationHasher hasher;

    @Autowired
    private EmailVerificationSendLimitProperties properties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private String resendKey(String email) {
        return "auth:email-verification-resend:" + hasher.hashEmail(email);
    }

    private String emailRequestsKey(String email) {
        return "auth:email-verification-email-requests:" + hasher.hashEmail(email);
    }

    private String ipRequestsKey(String ipAddress) {
        return "auth:email-verification-ip-requests:" + hasher.hashIpAddress(ipAddress);
    }

    private long ttlMillis(String key) {
        // -1은 TTL 없음, -2는 키 없음
        return redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("application.yml의 발송 제한 값이 바인딩된다")
    void bindsSendLimitProperties() {
        assertThat(properties.resendInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.emailMaxRequests()).isEqualTo(10);
        assertThat(properties.emailWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.ipMaxRequests()).isEqualTo(30);
        assertThat(properties.ipWindow()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("재발송 간격은 첫 호출만 시작하고, 60초 이하의 TTL이 걸리며 다른 이메일은 영향이 없다")
    void startsResendIntervalOnce() {
        // when
        boolean first = repository.tryStartResendInterval(EMAIL);
        boolean second = repository.tryStartResendInterval(EMAIL);

        // then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(ttlMillis(resendKey(EMAIL)))
                .isBetween(Duration.ofSeconds(58).toMillis(), Duration.ofSeconds(60).toMillis());
        assertThat(repository.tryStartResendInterval(OTHER_EMAIL)).isTrue();
    }

    @Test
    @DisplayName("재발송 간격이 지나면 다시 시작할 수 있다")
    void restartsResendIntervalAfterExpiry() {
        // given: 60초를 기다릴 수 없어 남은 시간만 줄인다
        repository.tryStartResendInterval(EMAIL);
        redisTemplate.expire(resendKey(EMAIL), Duration.ofMillis(200));

        // when & then
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50))
                .until(() -> !redisTemplate.hasKey(resendKey(EMAIL)));
        assertThat(repository.tryStartResendInterval(EMAIL)).isTrue();
    }

    @Test
    @DisplayName("같은 이메일로 동시에 재발송 간격을 시작해도 한 요청만 성공한다")
    void startsResendIntervalAtomically() throws Exception {
        // when
        List<Boolean> results = runConcurrently(20, () -> repository.tryStartResendInterval(EMAIL));

        // then
        assertThat(results).containsOnlyOnce(true).hasSize(20);
    }

    @Test
    @DisplayName("이메일별 횟수는 1씩 오르고 첫 증가 때 24시간 이하의 TTL이 걸리며, 다른 이메일은 따로 센다")
    void incrementsEmailRequestsWithWindow() {
        // when & then
        assertThat(repository.incrementEmailRequests(EMAIL)).isEqualTo(1L);
        assertThat(ttlMillis(emailRequestsKey(EMAIL)))
                .isBetween(Duration.ofHours(24).minusSeconds(2).toMillis(), Duration.ofHours(24).toMillis());
        assertThat(repository.incrementEmailRequests(EMAIL)).isEqualTo(2L);
        assertThat(repository.incrementEmailRequests(OTHER_EMAIL)).isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get(emailRequestsKey(EMAIL))).isEqualTo("2");
    }

    @Test
    @DisplayName("IP별 횟수는 1씩 오르고 첫 증가 때 1시간 이하의 TTL이 걸리며, 다른 IP는 따로 센다")
    void incrementsIpRequestsWithWindow() {
        // when & then
        assertThat(repository.incrementIpRequests(IP)).isEqualTo(1L);
        assertThat(ttlMillis(ipRequestsKey(IP)))
                .isBetween(Duration.ofHours(1).minusSeconds(2).toMillis(), Duration.ofHours(1).toMillis());
        assertThat(repository.incrementIpRequests(IP)).isEqualTo(2L);
        assertThat(repository.incrementIpRequests("198.51.100.1")).isEqualTo(1L);
        assertThat(repository.incrementIpRequests("2001:db8::1")).isEqualTo(1L);
    }

    @Test
    @DisplayName("고정 구간: 이후 증가는 TTL을 다시 걸지 않아 계속 요청해도 구간이 늘어나지 않는다")
    void laterIncrementsDoNotExtendWindow() {
        // given: 구간이 거의 끝난 상태를 만든다
        repository.incrementEmailRequests(EMAIL);
        repository.incrementIpRequests(IP);
        redisTemplate.expire(emailRequestsKey(EMAIL), Duration.ofSeconds(30));
        redisTemplate.expire(ipRequestsKey(IP), Duration.ofSeconds(30));

        // when
        repository.incrementEmailRequests(EMAIL);
        repository.incrementIpRequests(IP);

        // then
        assertThat(ttlMillis(emailRequestsKey(EMAIL))).isBetween(1L, Duration.ofSeconds(30).toMillis());
        assertThat(ttlMillis(ipRequestsKey(IP))).isBetween(1L, Duration.ofSeconds(30).toMillis());
    }

    @Test
    @DisplayName("구간이 끝나면 횟수가 1부터 다시 시작한다")
    void restartsCountAfterWindow() {
        // given
        repository.incrementEmailRequests(EMAIL);
        repository.incrementEmailRequests(EMAIL);
        redisTemplate.expire(emailRequestsKey(EMAIL), Duration.ofMillis(200));

        // when & then
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50))
                .until(() -> !redisTemplate.hasKey(emailRequestsKey(EMAIL)));
        assertThat(repository.incrementEmailRequests(EMAIL)).isEqualTo(1L);
        assertThat(ttlMillis(emailRequestsKey(EMAIL))).isGreaterThan(Duration.ofHours(23).toMillis());
    }

    @Test
    @DisplayName("동시에 횟수를 올려도 요청마다 서로 다른 값을 받는다")
    void incrementsAtomically() throws Exception {
        // when
        List<Long> results = runConcurrently(40, () -> repository.incrementIpRequests(IP));

        // then: IP 한도 30회를 동시 요청으로 넘길 수 없다(30 이하를 받는 요청은 30개뿐)
        assertThat(results).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 40).boxed().toList());
        assertThat(ttlMillis(ipRequestsKey(IP))).isPositive();
    }

    @Test
    @DisplayName("Redis 키·값에 이메일·IP 원문이 없다")
    void storesNoRawEmailOrIp() {
        // when
        repository.tryStartResendInterval(EMAIL);
        repository.incrementEmailRequests(EMAIL);
        repository.incrementIpRequests(IP);

        // then
        assertThat(redisTemplate.keys("*"))
                .containsExactlyInAnyOrder(resendKey(EMAIL), emailRequestsKey(EMAIL), ipRequestsKey(IP))
                .allSatisfy(key -> assertThat(key).doesNotContain("user").doesNotContain("example").doesNotContain(IP));
        assertThat(redisTemplate.opsForValue().get(resendKey(EMAIL))).isEqualTo("1");
        assertThat(redisTemplate.opsForValue().get(emailRequestsKey(EMAIL))).isEqualTo("1");
        assertThat(redisTemplate.opsForValue().get(ipRequestsKey(IP))).isEqualTo("1");
    }

    private static <T> List<T> runConcurrently(int requests, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            tasks.add(task);
        }
        List<T> results = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        try {
            for (Future<T> future : executor.invokeAll(tasks)) {
                results.add(future.get());
            }
        } finally {
            executor.shutdownNow();
        }
        return results;
    }
}
