package org.example.grab.domain.auth.repository;

import org.example.grab.domain.auth.support.RefreshTokenConfig;
import org.example.grab.domain.auth.support.RefreshTokenGenerator;
import org.example.grab.domain.auth.support.RefreshTokenHasher;
import org.example.grab.domain.auth.support.RefreshTokenTtlCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/*
    GR-35 M01-04·M02-02: 교체·재사용 확인·세션 폐기 스크립트를 실제 Redis(Testcontainers)에서 확인한다.
    Lua 스크립트의 원자성과 분기는 mock으로 확인할 수 없다.
    유예는 짧게(500ms) 두어 유예 안·밖을 모두 기다려 확인한다. 저장 TTL(idle-ttl)은 유예보다 길어야 표식이 남는다.
 */
@DataRedisTest
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({RefreshTokenRepository.class, RefreshTokenTtlCalculator.class, RefreshTokenConfig.class})
@Testcontainers
class RefreshTokenRotationIntegrationTest {

    private static final Duration IDLE_TTL = Duration.ofMinutes(10);
    private static final Duration REUSE_GRACE = Duration.ofMillis(500);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 컨테이너 Redis는 인증이 없다. 빈 비밀번호는 "비밀번호 없음"으로 처리된다(GR-33 M01-02)
        registry.add("spring.data.redis.password", () -> "");
        registry.add("grab.auth.refresh-token.idle-ttl", () -> IDLE_TTL.toString());
        registry.add("grab.auth.refresh-token.reuse-grace", () -> REUSE_GRACE.toString());
    }

    private final RefreshTokenGenerator generator = new RefreshTokenGenerator();

    @Autowired
    private RefreshTokenRepository repository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // 컨테이너는 이 클래스의 테스트가 함께 쓰므로 테스트마다 키를 비운다
    @AfterEach
    void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private static RefreshTokenSession newSession() {
        return new RefreshTokenSession(7L, UUID.randomUUID(), Instant.now().plus(30, ChronoUnit.DAYS));
    }

    private String currentHash(RefreshTokenSession session) {
        return redisTemplate.opsForValue().get(RefreshTokenHasher.toSessionKey(session.sessionId()));
    }

    // 유예가 지나도록 기다린다. 표식 TTL(idle-ttl)보다 충분히 짧다
    private static void waitPastGrace() throws InterruptedException {
        Thread.sleep(REUSE_GRACE.plusMillis(200).toMillis());
    }

    @Test
    @DisplayName("교체하면 옛 키는 지워지고 새 키·세션 키·사용됨 표식이 같은 TTL로 저장된다")
    void rotateReplacesTokenAndLeavesUsedMarker() {
        // given
        RefreshTokenSession session = newSession();
        String oldToken = generator.generate();
        String newToken = generator.generate();
        repository.save(oldToken, session);

        // when
        Optional<Duration> ttl = repository.rotate(oldToken, newToken, session);

        // then
        assertThat(ttl).contains(IDLE_TTL);
        assertThat(repository.find(oldToken)).isEmpty();
        assertThat(repository.find(newToken)).contains(session);
        assertThat(currentHash(session)).isEqualTo(RefreshTokenHasher.hash(newToken));

        String usedKey = RefreshTokenHasher.toUsedKey(oldToken);
        Map<Object, Object> marker = redisTemplate.opsForHash().entries(usedKey);
        assertThat(marker).containsEntry("sessionId", session.sessionId().toString()).containsKey("rotatedAt");
        assertThat(marker.values()).noneMatch(value -> value.toString().contains(oldToken));

        long newTokenTtl = redisTemplate.getExpire(RefreshTokenHasher.toKey(newToken), TimeUnit.SECONDS);
        assertThat(newTokenTtl).isBetween(IDLE_TTL.toSeconds() - 2, IDLE_TTL.toSeconds());
        assertThat(redisTemplate.getExpire(RefreshTokenHasher.toSessionKey(session.sessionId()), TimeUnit.SECONDS))
                .isEqualTo(newTokenTtl);
        assertThat(redisTemplate.getExpire(usedKey, TimeUnit.SECONDS)).isEqualTo(newTokenTtl);
    }

    @Test
    @DisplayName("옛 토큰 키가 이미 없으면 교체하지 않고 새 키도 저장하지 않는다")
    void rotateDoesNothingWhenOldTokenIsGone() {
        // given: 한 번 교체해 옛 토큰 키가 없다
        RefreshTokenSession session = newSession();
        String oldToken = generator.generate();
        String firstNew = generator.generate();
        repository.save(oldToken, session);
        repository.rotate(oldToken, firstNew, session);
        String secondNew = generator.generate();

        // when
        Optional<Duration> ttl = repository.rotate(oldToken, secondNew, session);

        // then
        assertThat(ttl).isEmpty();
        assertThat(redisTemplate.hasKey(RefreshTokenHasher.toKey(secondNew))).isFalse();
        assertThat(currentHash(session)).isEqualTo(RefreshTokenHasher.hash(firstNew));
    }

    // 완료 조건 5: 같은 Refresh Token의 동시 재발급 요청 중 하나만 성공한다
    @Test
    @DisplayName("같은 토큰으로 동시에 교체하면 하나만 성공하고 새 토큰 키도 하나만 남는다")
    void concurrentRotationSucceedsOnlyOnce() throws Exception {
        // given
        int requests = 20;
        RefreshTokenSession session = newSession();
        String oldToken = generator.generate();
        repository.save(oldToken, session);

        List<String> newTokens = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            newTokens.add(generator.generate());
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests);

        // when
        List<Future<Optional<Duration>>> results = new ArrayList<>();
        try {
            for (String newToken : newTokens) {
                Callable<Optional<Duration>> task = () -> {
                    start.await();
                    return repository.rotate(oldToken, newToken, session);
                };
                results.add(executor.submit(task));
            }
            start.countDown();

            // then
            long succeeded = 0;
            for (Future<Optional<Duration>> result : results) {
                if (result.get(10, TimeUnit.SECONDS).isPresent()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        long storedNewTokens = newTokens.stream()
                .filter(token -> redisTemplate.hasKey(RefreshTokenHasher.toKey(token)))
                .count();
        assertThat(storedNewTokens).isEqualTo(1);
        assertThat(newTokens.stream().map(RefreshTokenHasher::hash)).contains(currentHash(session));
    }

    @Test
    @DisplayName("교체된 적 없는 토큰은 재사용이 아니라 NOT_FOUND다")
    void unknownTokenIsNotFound() {
        // when
        RefreshTokenReuseCheck result = repository.checkReuse(generator.generate());

        // then
        assertThat(result.status()).isEqualTo(RefreshTokenReuseCheck.Status.NOT_FOUND);
        assertThat(result.sessionId()).isNull();
    }

    // M00-03: 여러 탭의 동시 재발급으로 보고 세션을 유지한다
    @Test
    @DisplayName("유예 안의 재사용은 WITHIN_GRACE이고 새 토큰은 계속 유효하다")
    void reuseWithinGraceKeepsSession() {
        // given
        RefreshTokenSession session = newSession();
        String oldToken = generator.generate();
        String newToken = generator.generate();
        repository.save(oldToken, session);
        repository.rotate(oldToken, newToken, session);

        // when
        RefreshTokenReuseCheck result = repository.checkReuse(oldToken);

        // then
        assertThat(result.status()).isEqualTo(RefreshTokenReuseCheck.Status.WITHIN_GRACE);
        assertThat(result.sessionId()).isEqualTo(session.sessionId());
        assertThat(repository.find(newToken)).contains(session);
        assertThat(currentHash(session)).isEqualTo(RefreshTokenHasher.hash(newToken));
    }

    // M00-02: 탈취로 보고 그 세션의 현재 토큰을 폐기한다
    @Test
    @DisplayName("유예가 지난 재사용은 REUSE_DETECTED이고 세션의 현재 토큰과 세션 키가 지워진다")
    void reuseAfterGraceRevokesSession() throws InterruptedException {
        // given: A → B → C로 두 번 교체해, 재사용한 A가 아니라 현재 토큰 C가 지워지는지 본다
        RefreshTokenSession session = newSession();
        String tokenA = generator.generate();
        String tokenB = generator.generate();
        String tokenC = generator.generate();
        repository.save(tokenA, session);
        repository.rotate(tokenA, tokenB, session);
        repository.rotate(tokenB, tokenC, session);

        RefreshTokenSession otherSession = newSession();
        String otherToken = generator.generate();
        repository.save(otherToken, otherSession);
        waitPastGrace();

        // when
        RefreshTokenReuseCheck result = repository.checkReuse(tokenA);

        // then
        assertThat(result.status()).isEqualTo(RefreshTokenReuseCheck.Status.REUSE_DETECTED);
        assertThat(result.sessionId()).isEqualTo(session.sessionId());
        assertThat(repository.find(tokenC)).isEmpty();
        assertThat(redisTemplate.hasKey(RefreshTokenHasher.toSessionKey(session.sessionId()))).isFalse();
        // 폐기된 세션의 현재 토큰으로는 더 이상 교체할 수 없다
        assertThat(repository.rotate(tokenC, generator.generate(), session)).isEmpty();
        // 다른 세션은 영향이 없다
        assertThat(repository.find(otherToken)).contains(otherSession);
    }

    @Test
    @DisplayName("세션 폐기는 현재 토큰 키와 세션 키를 지우고, 반복해도 예외가 없으며 다른 세션은 그대로다")
    void revokeSessionRemovesCurrentTokenOnly() {
        // given
        RefreshTokenSession session = newSession();
        String token = generator.generate();
        repository.save(token, session);

        RefreshTokenSession otherSession = newSession();
        String otherToken = generator.generate();
        repository.save(otherToken, otherSession);

        // when
        repository.revokeSession(session.sessionId());

        // then
        assertThat(repository.find(token)).isEmpty();
        assertThat(redisTemplate.hasKey(RefreshTokenHasher.toSessionKey(session.sessionId()))).isFalse();
        assertThatCode(() -> repository.revokeSession(session.sessionId())).doesNotThrowAnyException();
        assertThatCode(() -> repository.revokeSession(UUID.randomUUID())).doesNotThrowAnyException();
        assertThat(repository.find(otherToken)).contains(otherSession);
    }
}
