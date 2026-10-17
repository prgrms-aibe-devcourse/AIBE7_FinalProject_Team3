package org.example.grab.domain.user.repository;

import org.example.grab.domain.user.entity.AuthProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/*
    GR-46 M02-01(M00-06): 실제 Redis(Testcontainers)에서 소셜 가입 컨텍스트 저장소를 확인한다.
    키는 저장소 코드를 쓰지 않고 테스트에서 따로 계산한 SHA-256으로 확인한다(EmailSignupContextRepositoryIntegrationTest와 같음).
    @DataRedisTest는 ObjectMapper를 만들지 않으므로 Jackson 자동 설정을 가져온다.
 */
@DataRedisTest
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import(OAuth2SignupContextRepository.class)
@Testcontainers
class OAuth2SignupContextRepositoryIntegrationTest {

    // SignupTokenGenerator가 만드는 형식(43자 Base64URL)의 테스트 값
    private static final String TOKEN = "Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo";
    private static final String OTHER_TOKEN = "Qp7_mR2bW9xN4cT1vY8zL3kH6sD0fJ5gA2eU7iO4nBt";
    private static final String SUB = "4021973658";
    private static final String EMAIL = "user@example.com";
    private static final OAuth2SignupContext CONTEXT = new OAuth2SignupContext(AuthProvider.KAKAO, SUB, EMAIL);

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
    private OAuth2SignupContextRepository repository;

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
        return "auth:oauth2-signup-context:" + HexFormat.of().formatHex(hashed);
    }

    @Test
    @DisplayName("저장하면 토큰 SHA-256 키에 컨텍스트 JSON이 15분 이하의 TTL로 남고, 키에 토큰·이메일·식별자 원문이 없다")
    void savesContextUnderHashedTokenKeyWithTtl() throws Exception {
        // when
        repository.save(TOKEN, CONTEXT);

        // then
        assertThat(redisTemplate.keys("*")).containsExactly(key(TOKEN));
        assertThat(key(TOKEN)).doesNotContain(TOKEN).doesNotContain("user").doesNotContain(SUB);
        assertThat(redisTemplate.opsForValue().get(key(TOKEN)))
                .contains("\"provider\":\"KAKAO\"", "\"providerUserId\":\"" + SUB + "\"", "\"email\":\"" + EMAIL + "\"")
                .doesNotContain(TOKEN);
        // -1은 TTL 없음, -2는 키 없음
        assertThat(redisTemplate.getExpire(key(TOKEN), TimeUnit.MILLISECONDS))
                .isBetween(Duration.ofMinutes(15).minusSeconds(2).toMillis(), Duration.ofMinutes(15).toMillis());
    }

    @Test
    @DisplayName("조회는 저장한 토큰의 컨텍스트만 돌려주고, 조회해도 컨텍스트가 지워지지 않는다")
    void findsContextWithoutConsuming() {
        // given
        repository.save(TOKEN, CONTEXT);

        // when & then: 닉네임·요청 값 오류 뒤 같은 토큰으로 다시 요청할 수 있어야 한다(M00-06)
        assertThat(repository.find(TOKEN)).contains(CONTEXT);
        assertThat(repository.find(TOKEN)).contains(CONTEXT);
        assertThat(repository.find(OTHER_TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("LOCAL 가입 컨텍스트와 키가 달라, 같은 토큰 값이라도 서로의 컨텍스트로 조회되지 않는다")
    void doesNotReadEmailSignupContext() throws Exception {
        // given: LOCAL 저장소가 쓰는 키 형식으로 이메일만 저장한다
        byte[] hashed = MessageDigest.getInstance("SHA-256").digest(TOKEN.getBytes(StandardCharsets.UTF_8));
        redisTemplate.opsForValue().set("auth:email-signup-context:" + HexFormat.of().formatHex(hashed), EMAIL);

        // when & then
        assertThat(repository.find(TOKEN)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("쿠키가 없거나 빈 토큰이면 조회는 비어 있고 삭제는 아무 일도 하지 않는다")
    void blankTokenIsEmptyAndDeleteIsNoop(String rawToken) {
        // given
        repository.save(TOKEN, CONTEXT);

        // when
        repository.delete(rawToken);

        // then
        assertThat(repository.find(rawToken)).isEmpty();
        assertThat(repository.find(TOKEN)).contains(CONTEXT);
    }

    @Test
    @DisplayName("삭제(소비)하면 다시 조회되지 않고, 다시 삭제해도 예외가 없으며 다른 토큰은 영향이 없다")
    void deleteConsumesContext() {
        // given
        OAuth2SignupContext other = new OAuth2SignupContext(AuthProvider.GOOGLE, "google-sub", "other@example.com");
        repository.save(TOKEN, CONTEXT);
        repository.save(OTHER_TOKEN, other);

        // when
        repository.delete(TOKEN);
        repository.delete(TOKEN);

        // then
        assertThat(repository.find(TOKEN)).isEmpty();
        assertThat(repository.find(OTHER_TOKEN)).contains(other);
    }

    @Test
    @DisplayName("TTL이 지나면 조회되지 않는다(만료와 없음을 구분하지 않음)")
    void expiredContextIsNotFound() throws Exception {
        // given: 15분을 기다릴 수 없어 저장된 키의 남은 시간만 줄인다
        repository.save(TOKEN, CONTEXT);
        redisTemplate.expire(key(TOKEN), Duration.ofMillis(200));

        // when & then
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50))
                .until(() -> repository.find(TOKEN).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json",
            "{\"provider\":\"KAKAO\",\"email\":\"user@example.com\"}",
            "{\"provider\":\"LOCAL\",\"providerUserId\":\"1\",\"email\":\"user@example.com\"}",
            "{\"provider\":\"NAVER\",\"providerUserId\":\"1\",\"email\":\"user@example.com\"}"
    })
    @DisplayName("읽을 수 없는 값(깨진 JSON·필수 필드 누락·LOCAL·모르는 제공자)은 없는 컨텍스트로 본다")
    void unreadableValueIsNotFound(String value) throws Exception {
        // given
        redisTemplate.opsForValue().set(key(TOKEN), value);

        // when & then
        assertThat(repository.find(TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("모르는 필드가 더해진 값도 읽는다(배포 중 형식이 섞여도 가입할 수 있다)")
    void ignoresUnknownFields() throws Exception {
        // given
        redisTemplate.opsForValue().set(key(TOKEN),
                "{\"provider\":\"KAKAO\",\"providerUserId\":\"" + SUB + "\",\"email\":\"" + EMAIL + "\",\"extra\":1}");

        // when & then
        assertThat(repository.find(TOKEN)).contains(CONTEXT);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("빈 토큰이나 빈 컨텍스트는 저장하지 않고, 예외 메시지에 값이 없다")
    void rejectsBlankTokenOrContext(String value) {
        assertThatThrownBy(() -> repository.save(value, CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(EMAIL);
        assertThatThrownBy(() -> repository.save(TOKEN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(TOKEN);
        assertThat(redisTemplate.keys("*")).isEmpty();
    }

    @Test
    @DisplayName("컨텍스트 값의 toString은 제공자 식별자와 이메일을 드러내지 않는다")
    void masksContextToString() {
        assertThat(CONTEXT.toString()).doesNotContain(SUB).doesNotContain(EMAIL).contains("KAKAO");
    }
}
