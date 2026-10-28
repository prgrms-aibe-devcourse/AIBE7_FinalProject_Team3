package org.example.grab.global.security.password;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// GR-178 M03-02: 비밀번호 해시 동시 실행 상한 설정의 검증과 바인딩(M00-02·M00-03)
class PasswordHashPropertiesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration RETRY_AFTER = Duration.ofSeconds(3);

    @Test
    @DisplayName("올바른 값이면 그대로 담는다")
    void holdsValidValues() {
        // when
        PasswordHashProperties properties = new PasswordHashProperties(4, TIMEOUT, RETRY_AFTER);

        // then
        assertThat(properties.maxConcurrent()).isEqualTo(4);
        assertThat(properties.acquireTimeout()).isEqualTo(TIMEOUT);
        assertThat(properties.retryAfter()).isEqualTo(RETRY_AFTER);
    }

    @Test
    @DisplayName("max-concurrent가 1보다 작으면 거부하고 1은 허용한다")
    void validatesMaxConcurrent() {
        assertRejected(0, TIMEOUT, RETRY_AFTER, "max-concurrent");
        assertRejected(-1, TIMEOUT, RETRY_AFTER, "max-concurrent");
        assertThat(new PasswordHashProperties(1, TIMEOUT, RETRY_AFTER).maxConcurrent()).isEqualTo(1);
    }

    @Test
    @DisplayName("acquire-timeout이 없거나 1밀리초보다 짧거나 나노초로 표현할 수 없으면 거부하고 1밀리초는 허용한다")
    void validatesAcquireTimeout() {
        assertRejected(4, null, RETRY_AFTER, "acquire-timeout");
        assertRejected(4, Duration.ZERO, RETRY_AFTER, "acquire-timeout");
        assertRejected(4, Duration.ofMillis(-1), RETRY_AFTER, "acquire-timeout");
        assertRejected(4, Duration.ofNanos(999_999), RETRY_AFTER, "acquire-timeout");
        // Semaphore.tryAcquire에 나노초로 넘기므로 long 나노초를 넘는 기간은 받지 않는다
        assertRejected(4, Duration.ofSeconds(Long.MAX_VALUE), RETRY_AFTER, "acquire-timeout");
        assertThat(new PasswordHashProperties(4, Duration.ofMillis(1), RETRY_AFTER).acquireTimeout())
                .isEqualTo(Duration.ofMillis(1));
    }

    @Test
    @DisplayName("retry-after가 없거나 1초보다 짧거나 1초 미만 단위가 있으면 거부하고 1초는 허용한다")
    void validatesRetryAfter() {
        assertRejected(4, TIMEOUT, null, "retry-after");
        assertRejected(4, TIMEOUT, Duration.ZERO, "retry-after");
        assertRejected(4, TIMEOUT, Duration.ofMillis(999), "retry-after");
        // Retry-After 헤더는 정수 초라 1.5초는 잘려 의도와 달라진다
        assertRejected(4, TIMEOUT, Duration.ofMillis(1500), "retry-after");
        assertThat(new PasswordHashProperties(4, TIMEOUT, Duration.ofSeconds(1)).retryAfter())
                .isEqualTo(Duration.ofSeconds(1));
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("grab.password-hash 설정을 바인딩한다")
    void bindsFromProperties() {
        contextRunner
                .withPropertyValues(
                        "grab.password-hash.max-concurrent=2",
                        "grab.password-hash.acquire-timeout=500ms",
                        "grab.password-hash.retry-after=5s")
                .run(context -> {
                    PasswordHashProperties properties = context.getBean(PasswordHashProperties.class);
                    assertThat(properties.maxConcurrent()).isEqualTo(2);
                    assertThat(properties.acquireTimeout()).isEqualTo(Duration.ofMillis(500));
                    assertThat(properties.retryAfter()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    @DisplayName("application.yml의 기본값은 상한 4, 대기 3초, Retry-After 3초다")
    void bindsDefaultsFromApplicationYml() {
        contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    PasswordHashProperties properties = context.getBean(PasswordHashProperties.class);
                    assertThat(properties.maxConcurrent()).isEqualTo(4);
                    assertThat(properties.acquireTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.retryAfter()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    @DisplayName("설정이 없거나 범위를 벗어나면 컨텍스트가 뜨지 않는다")
    void failsToStartWithInvalidProperties() {
        contextRunner
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues(
                        "grab.password-hash.max-concurrent=0",
                        "grab.password-hash.acquire-timeout=3s",
                        "grab.password-hash.retry-after=3s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues(
                        "grab.password-hash.max-concurrent=4",
                        "grab.password-hash.acquire-timeout=3s",
                        "grab.password-hash.retry-after=1500ms")
                .run(context -> assertThat(context).hasFailed());
    }

    private static void assertRejected(int maxConcurrent, Duration acquireTimeout, Duration retryAfter, String name) {
        assertThatThrownBy(() -> new PasswordHashProperties(maxConcurrent, acquireTimeout, retryAfter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(name);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PasswordHashProperties.class)
    static class PropertiesConfig {
    }
}
