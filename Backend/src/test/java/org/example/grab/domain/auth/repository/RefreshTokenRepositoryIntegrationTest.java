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
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

/*
    GR-33 M04: 실제 Redis(Testcontainers)에서 저장소를 확인한다.
    - 공식 Redis 모듈 없이 GenericContainer로 띄운다. 저장소는 문자열 명령만 쓰므로 충분하다
    - @DataRedisTest는 Spring Data Redis 리포지토리만 스캔하므로 저장소와 의존 빈을 직접 가져온다
    - 운영과 같은 ObjectMapper로 직렬화 형식을 확인하도록 Boot의 Jackson 자동 설정을 가져온다
 */
@DataRedisTest
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({RefreshTokenRepository.class, RefreshTokenTtlCalculator.class, RefreshTokenConfig.class})
@Testcontainers
class RefreshTokenRepositoryIntegrationTest {

    private static final Duration IDLE_TTL = Duration.ofSeconds(2);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 컨테이너 Redis는 인증이 없다. 빈 비밀번호는 "비밀번호 없음"으로 처리된다(M01-02)
        registry.add("spring.data.redis.password", () -> "");
        // 만료를 실제로 기다려 확인하도록 짧게 둔다(M04-02). 바로 조회하는 테스트에는 충분히 길다
        registry.add("grab.auth.refresh-token.idle-ttl", () -> IDLE_TTL.toString());
    }

    private final RefreshTokenGenerator generator = new RefreshTokenGenerator();

    @Autowired
    private RefreshTokenRepository repository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // 컨테이너는 이 클래스의 테스트가 함께 쓰므로 테스트마다 키를 비운다
    @AfterEach
    void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private static RefreshTokenSession sessionExpiringAt(Instant sessionExpiresAt) {
        return new RefreshTokenSession(7L, UUID.randomUUID(), sessionExpiresAt);
    }

    @Test
    @DisplayName("저장 직후 해시 키만 있고 원문 키는 없으며, TTL이 설정값 이하이고 만료 없음(-1)이 아니다")
    void savesUnderHashedKeyWithTtl() {
        // given
        String rawToken = generator.generate();

        // when
        repository.save(rawToken, sessionExpiringAt(Instant.now().plus(30, ChronoUnit.DAYS)));

        // then
        String key = RefreshTokenHasher.toKey(rawToken);
        assertThat(redisTemplate.keys("*")).containsExactly(key);
        assertThat(redisTemplate.hasKey(rawToken)).isFalse();
        assertThat(redisTemplate.hasKey("auth:refresh-token:" + rawToken)).isFalse();
        // -1은 TTL 없음, -2는 키 없음
        assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, IDLE_TTL.toMillis());
    }

    @Test
    @DisplayName("TTL이 지나면 토큰이 조회되지 않는다")
    void expiredTokenIsNotFound() {
        // given
        String rawToken = generator.generate();
        repository.save(rawToken, sessionExpiringAt(Instant.now().plus(30, ChronoUnit.DAYS)));
        assertThat(repository.find(rawToken)).isPresent();

        // when & then: Redis가 키를 지울 때까지 기다린다
        await().atMost(IDLE_TTL.plusSeconds(3)).pollInterval(Duration.ofMillis(100))
                .until(() -> repository.find(rawToken).isEmpty());
        assertThat(redisTemplate.hasKey(RefreshTokenHasher.toKey(rawToken))).isFalse();
    }

    @Test
    @DisplayName("삭제 후 조회는 빈 결과이고, 없는 토큰 삭제는 예외가 없으며, 다른 토큰은 영향이 없다")
    void deleteRemovesOnlyThatToken() {
        // given
        String deleted = generator.generate();
        String kept = generator.generate();
        RefreshTokenSession keptSession = sessionExpiringAt(Instant.now().plus(30, ChronoUnit.DAYS));
        repository.save(deleted, sessionExpiringAt(Instant.now().plus(30, ChronoUnit.DAYS)));
        repository.save(kept, keptSession);

        // when
        repository.delete(deleted);

        // then
        assertThat(repository.find(deleted)).isEmpty();
        assertThatCode(() -> repository.delete(deleted)).doesNotThrowAnyException();
        assertThatCode(() -> repository.delete(generator.generate())).doesNotThrowAnyException();
        assertThat(repository.find(kept)).contains(keptSession);
    }

    @Test
    @DisplayName("절대 만료가 비활동 TTL보다 가까우면 Redis TTL이 save가 돌려준 남은 시간과 같다")
    void ttlFollowsAbsoluteExpiryWhenCloser() {
        // given: 절대 만료까지 1.5초 남아 비활동 TTL(2초)보다 짧다
        String rawToken = generator.generate();
        RefreshTokenSession session = sessionExpiringAt(Instant.now().plusMillis(1_500));

        // when
        Duration ttl = repository.save(rawToken, session);

        // then: 반환값은 남은 시간이고, Redis TTL은 저장 후 흐른 시간만큼만 짧다
        assertThat(ttl).isLessThan(IDLE_TTL).isPositive();
        Long redisTtlMillis = redisTemplate.getExpire(RefreshTokenHasher.toKey(rawToken), TimeUnit.MILLISECONDS);
        assertThat(redisTtlMillis).isBetween(ttl.toMillis() - 300, ttl.toMillis());
    }

    @Test
    @DisplayName("저장한 토큰을 조회하면 저장한 세 필드가 나온다")
    void findReturnsSavedSession() {
        // given: Redis·JSON 왕복에서 정밀도가 바뀌지 않는지 보도록 밀리초까지 둔다
        String rawToken = generator.generate();
        RefreshTokenSession session = new RefreshTokenSession(
                7L, UUID.randomUUID(), Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS));

        // when
        repository.save(rawToken, session);

        // then
        assertThat(repository.find(rawToken)).contains(session);
    }

    @Test
    @DisplayName("운영 ObjectMapper로 저장한 값은 클래스 이름 없는 JSON이고 sessionExpiresAt은 ISO-8601 문자열이다")
    void storesPlainJsonWithBootObjectMapper() {
        // given
        String rawToken = generator.generate();
        RefreshTokenSession session = new RefreshTokenSession(
                7L, UUID.fromString("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f"), Instant.parse("2099-10-31T06:00:00Z"));

        // when
        repository.save(rawToken, session);

        // then
        String stored = redisTemplate.opsForValue().get(RefreshTokenHasher.toKey(rawToken));
        assertThat(objectMapper.readTree(stored)).isEqualTo(objectMapper.readTree(
                "{\"userId\":7,\"sessionId\":\"3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f\",\"sessionExpiresAt\":\"2099-10-31T06:00:00Z\"}"));
    }
}
