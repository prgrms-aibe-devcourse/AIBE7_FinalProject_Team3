package org.example.grab.domain.user.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/*
    GR-61 M02-03: 실제 Redis(Testcontainers)에서 가입 컨텍스트 저장소를 확인한다.
    키는 저장소 코드를 쓰지 않고 테스트에서 따로 계산한 SHA-256으로 확인한다.
 */
@DataRedisTest
@Import(EmailSignupContextRepository.class)
@Testcontainers
class EmailSignupContextRepositoryIntegrationTest {

    // EmailSignupTokenGenerator가 만드는 형식(43자 Base64URL)의 테스트 값
    private static final String TOKEN = "Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo";
    private static final String OTHER_TOKEN = "Qp7_mR2bW9xN4cT1vY8zL3kH6sD0fJ5gA2eU7iO4nBt";
    private static final String EMAIL = "user@example.com";

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
    private EmailSignupContextRepository repository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private static String key(String rawToken) throws Exception {
        byte[] hashed = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
        return "auth:email-signup-context:" + HexFormat.of().formatHex(hashed);
    }

    @Test
    @DisplayName("저장하면 토큰 SHA-256 키에 이메일이 15분 이하의 TTL로 남고, 키에 토큰·이메일 원문이 없다")
    void savesEmailUnderHashedTokenKeyWithTtl() throws Exception {
        // when
        repository.save(TOKEN, EMAIL);

        // then
        assertThat(redisTemplate.keys("*")).containsExactly(key(TOKEN));
        assertThat(key(TOKEN)).doesNotContain(TOKEN).doesNotContain("user");
        assertThat(redisTemplate.opsForValue().get(key(TOKEN))).isEqualTo(EMAIL).doesNotContain(TOKEN);
        // -1은 TTL 없음, -2는 키 없음
        assertThat(redisTemplate.getExpire(key(TOKEN), TimeUnit.MILLISECONDS))
                .isBetween(Duration.ofMinutes(15).minusSeconds(2).toMillis(), Duration.ofMinutes(15).toMillis());
    }

    @Test
    @DisplayName("조회는 저장한 토큰의 이메일만 돌려주고, 조회해도 컨텍스트가 지워지지 않는다")
    void findsEmailWithoutConsuming() {
        // given
        repository.save(TOKEN, EMAIL);

        // when & then: 요청 값 오류 뒤 같은 토큰으로 다시 요청할 수 있어야 한다(M00-03)
        assertThat(repository.findEmail(TOKEN)).contains(EMAIL);
        assertThat(repository.findEmail(TOKEN)).contains(EMAIL);
        assertThat(repository.findEmail(OTHER_TOKEN)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("쿠키가 없거나 빈 토큰이면 조회는 비어 있고 삭제는 아무 일도 하지 않는다")
    void blankTokenIsEmptyAndDeleteIsNoop(String rawToken) {
        // given
        repository.save(TOKEN, EMAIL);

        // when
        repository.delete(rawToken);

        // then
        assertThat(repository.findEmail(rawToken)).isEmpty();
        assertThat(repository.findEmail(TOKEN)).contains(EMAIL);
    }

    @Test
    @DisplayName("삭제(소비)하면 다시 조회되지 않고, 다시 삭제해도 예외가 없으며 다른 토큰은 영향이 없다")
    void deleteConsumesContext() {
        // given
        repository.save(TOKEN, EMAIL);
        repository.save(OTHER_TOKEN, "other@example.com");

        // when
        repository.delete(TOKEN);
        repository.delete(TOKEN);

        // then
        assertThat(repository.findEmail(TOKEN)).isEmpty();
        assertThat(repository.findEmail(OTHER_TOKEN)).contains("other@example.com");
    }

    @Test
    @DisplayName("TTL이 지나면 조회되지 않는다(만료와 없음을 구분하지 않음)")
    void expiredContextIsNotFound() throws Exception {
        // given: 15분을 기다릴 수 없어 저장된 키의 남은 시간만 줄인다
        repository.save(TOKEN, EMAIL);
        redisTemplate.expire(key(TOKEN), Duration.ofMillis(200));

        // when & then
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50))
                .until(() -> repository.findEmail(TOKEN).isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("빈 토큰이나 빈 이메일은 저장하지 않고, 예외 메시지에 값이 없다")
    void rejectsBlankTokenOrEmail(String value) {
        assertThatThrownBy(() -> repository.save(value, EMAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(EMAIL);
        assertThatThrownBy(() -> repository.save(TOKEN, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(TOKEN);
        assertThat(redisTemplate.keys("*")).isEmpty();
    }
}
