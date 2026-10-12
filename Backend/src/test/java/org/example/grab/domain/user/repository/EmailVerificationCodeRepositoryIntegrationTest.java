package org.example.grab.domain.user.repository;

import org.example.grab.domain.user.support.EmailVerificationConfig;
import org.example.grab.domain.user.support.EmailVerificationHasher;
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
    GR-61 M02-01: 실제 Redis(Testcontainers)에서 인증 코드 저장소를 확인한다.
    RefreshTokenRepositoryIntegrationTest와 같이 GenericContainer로 띄우고 저장소와 의존 빈을 직접 가져온다.
    HMAC 키는 테스트 설정(src/test/resources/config/application.yml)의 테스트 전용 값을 쓴다.
 */
@DataRedisTest
@Import({EmailVerificationCodeRepository.class, EmailVerificationHasher.class, EmailVerificationConfig.class})
@Testcontainers
class EmailVerificationCodeRepositoryIntegrationTest {

    private static final String EMAIL = "user@example.com";
    private static final String CODE = "482913";

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
    private EmailVerificationCodeRepository repository;

    @Autowired
    private EmailVerificationHasher hasher;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private String codeKey(String email) {
        return "auth:email-verification-code:" + hasher.hashEmail(email);
    }

    private String attemptsKey(String email) {
        return "auth:email-verification-attempts:" + hasher.hashEmail(email);
    }

    @Test
    @DisplayName("저장하면 이메일 HMAC 키에 코드 HMAC만 남고, 키·값에 이메일·코드 원문이 없으며 TTL이 5분 이하다")
    void savesHashedCodeUnderHashedEmailKeyWithTtl() {
        // when
        repository.save(EMAIL, CODE);

        // then
        assertThat(redisTemplate.keys("*")).containsExactly(codeKey(EMAIL));
        String stored = redisTemplate.opsForValue().get(codeKey(EMAIL));
        assertThat(stored).isEqualTo(hasher.hashVerificationCode(CODE));
        assertThat(codeKey(EMAIL)).doesNotContain(EMAIL).doesNotContain("user");
        assertThat(stored).doesNotContain(CODE);
        // -1은 TTL 없음, -2는 키 없음
        assertThat(redisTemplate.getExpire(codeKey(EMAIL), TimeUnit.MILLISECONDS))
                .isBetween(Duration.ofMinutes(5).minusSeconds(2).toMillis(), Duration.ofMinutes(5).toMillis());
    }

    @Test
    @DisplayName("저장한 코드를 조회해 비교하면 같은 코드만 일치한다")
    void findsSavedCodeHash() {
        // given
        repository.save(EMAIL, CODE);

        // when
        String stored = repository.findCodeHash(EMAIL).orElseThrow();

        // then
        assertThat(hasher.matchesVerificationCode(CODE, stored)).isTrue();
        assertThat(hasher.matchesVerificationCode("482914", stored)).isFalse();
        assertThat(repository.findCodeHash("other@example.com")).isEmpty();
    }

    @Test
    @DisplayName("같은 이메일로 다시 저장하면 이전 코드는 폐기되고 시도 횟수와 TTL이 처음부터 다시 시작한다")
    void saveOverwritesPreviousCodeAndResetsAttempts() {
        // given
        repository.save(EMAIL, CODE);
        repository.incrementAttempts(EMAIL);
        repository.incrementAttempts(EMAIL);
        redisTemplate.expire(codeKey(EMAIL), Duration.ofSeconds(30));

        // when
        repository.save(EMAIL, "105277");

        // then
        String stored = repository.findCodeHash(EMAIL).orElseThrow();
        assertThat(hasher.matchesVerificationCode("105277", stored)).isTrue();
        assertThat(hasher.matchesVerificationCode(CODE, stored)).isFalse();
        assertThat(redisTemplate.hasKey(attemptsKey(EMAIL))).isFalse();
        assertThat(redisTemplate.getExpire(codeKey(EMAIL), TimeUnit.SECONDS)).isGreaterThan(30L);
        assertThat(repository.incrementAttempts(EMAIL)).isEqualTo(1L);
    }

    @Test
    @DisplayName("시도 횟수는 1씩 오르고, 첫 증가 때 5분 이하의 TTL이 걸린다")
    void incrementsAttemptsWithTtl() {
        // given
        repository.save(EMAIL, CODE);

        // when & then
        assertThat(repository.incrementAttempts(EMAIL)).isEqualTo(1L);
        assertThat(redisTemplate.getExpire(attemptsKey(EMAIL), TimeUnit.MILLISECONDS))
                .isBetween(1L, Duration.ofMinutes(5).toMillis());
        assertThat(repository.incrementAttempts(EMAIL)).isEqualTo(2L);
        assertThat(repository.incrementAttempts(EMAIL)).isEqualTo(3L);
        assertThat(redisTemplate.opsForValue().get(attemptsKey(EMAIL))).isEqualTo("3");
    }

    @Test
    @DisplayName("동시에 시도 횟수를 올려도 요청마다 서로 다른 값을 받는다")
    void incrementsAttemptsAtomically() throws Exception {
        // given
        repository.save(EMAIL, CODE);
        int requests = 20;
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            tasks.add(() -> repository.incrementAttempts(EMAIL));
        }

        // when
        List<Long> results = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        try {
            for (Future<Long> future : executor.invokeAll(tasks)) {
                results.add(future.get());
            }
        } finally {
            executor.shutdownNow();
        }

        // then: 5회 제한을 동시 요청으로 넘길 수 없다(5 이하를 받는 요청은 5개뿐)
        assertThat(results).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, requests).boxed().toList());
    }

    @Test
    @DisplayName("삭제하면 코드와 시도 횟수가 사라지고, 코드를 지운 첫 호출만 true이며 다른 이메일은 영향이 없다")
    void deleteRemovesCodeOnce() {
        // given
        repository.save(EMAIL, CODE);
        repository.incrementAttempts(EMAIL);
        repository.save("other@example.com", "111111");

        // when
        boolean first = repository.delete(EMAIL);
        boolean second = repository.delete(EMAIL);

        // then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(repository.findCodeHash(EMAIL)).isEmpty();
        assertThat(redisTemplate.hasKey(attemptsKey(EMAIL))).isFalse();
        assertThat(repository.findCodeHash("other@example.com")).isPresent();
    }

    @Test
    @DisplayName("TTL이 지나면 코드가 조회되지 않는다")
    void expiredCodeIsNotFound() {
        // given: 5분을 기다릴 수 없어 저장된 키의 남은 시간만 줄인다
        repository.save(EMAIL, CODE);
        redisTemplate.expire(codeKey(EMAIL), Duration.ofMillis(200));

        // when & then
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50))
                .until(() -> repository.findCodeHash(EMAIL).isEmpty());
        assertThat(redisTemplate.hasKey(codeKey(EMAIL))).isFalse();
    }
}
