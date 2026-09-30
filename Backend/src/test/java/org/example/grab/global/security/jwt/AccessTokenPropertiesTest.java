package org.example.grab.global.security.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokenPropertiesTest {

    // 디코딩하면 44바이트. 테스트 전용 값이다
    private static final String SECRET = base64("grab-test-only-jwt-secret-not-for-production");

    @Test
    @DisplayName("올바른 값이면 그대로 담고, 서명 키는 디코딩한 바이트로 돌려준다")
    void holdsValidValues() {
        // when
        AccessTokenProperties properties = new AccessTokenProperties(Duration.ofMinutes(15), "grab", "grab-api", SECRET);

        // then
        assertThat(properties.ttl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.issuer()).isEqualTo("grab");
        assertThat(properties.audience()).isEqualTo("grab-api");
        assertThat(new String(properties.secretBytes(), StandardCharsets.UTF_8))
                .isEqualTo("grab-test-only-jwt-secret-not-for-production");
    }

    @Test
    @DisplayName("secretBytes는 호출할 때마다 새 배열을 돌려줘, 받은 배열을 바꿔도 설정이 바뀌지 않는다")
    void returnsCopyOfSecretBytes() {
        // given
        AccessTokenProperties properties = new AccessTokenProperties(Duration.ofMinutes(15), "grab", "grab-api", SECRET);
        byte[] first = properties.secretBytes();

        // when
        first[0] = 0;

        // then
        assertThat(properties.secretBytes()[0]).isEqualTo((byte) 'g');
    }

    @Test
    @DisplayName("ttl이 없거나 0 이하이면 거부한다")
    void rejectsNonPositiveTtl() {
        assertThatThrownBy(() -> new AccessTokenProperties(null, "grab", "grab-api", SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
        assertThatThrownBy(() -> new AccessTokenProperties(Duration.ZERO, "grab", "grab-api", SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
        assertThatThrownBy(() -> new AccessTokenProperties(Duration.ofMinutes(-1), "grab", "grab-api", SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("issuer·audience가 비어 있으면 거부한다")
    void rejectsBlankIssuerOrAudience(String blank) {
        assertThatThrownBy(() -> new AccessTokenProperties(Duration.ofMinutes(15), blank, "grab-api", SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issuer");
        assertThatThrownBy(() -> new AccessTokenProperties(Duration.ofMinutes(15), "grab", blank, SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audience");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("서명 키가 비어 있으면 거부한다")
    void rejectsBlankSecret(String blank) {
        assertThatThrownBy(() -> new AccessTokenProperties(Duration.ofMinutes(15), "grab", "grab-api", blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비어 있을 수 없습니다");
    }

    @Test
    @DisplayName("서명 키가 Base64가 아니면 거부하고, 메시지와 cause에 키 문자가 남지 않는다")
    void rejectsNonBase64SecretWithoutLeakingIt() {
        // given
        String notBase64 = "not-base64-secret-value!!";

        // when, then
        assertThatThrownBy(() -> new AccessTokenProperties(Duration.ofMinutes(15), "grab", "grab-api", notBase64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base64")
                .hasMessageNotContaining(notBase64)
                .hasNoCause();
    }

    @Test
    @DisplayName("디코딩한 서명 키가 32바이트 미만이면 거부하고, 32바이트면 통과한다")
    void requiresAtLeast32ByteSecret() {
        // given
        String bytes31 = base64("a".repeat(31));
        String bytes32 = base64("a".repeat(32));

        // when, then
        assertThatThrownBy(() -> new AccessTokenProperties(Duration.ofMinutes(15), "grab", "grab-api", bytes31))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32바이트 이상")
                .hasMessageContaining("현재 31바이트")
                .hasMessageNotContaining(bytes31);
        assertThat(new AccessTokenProperties(Duration.ofMinutes(15), "grab", "grab-api", bytes32).secretBytes())
                .hasSize(32);
    }

    @Test
    @DisplayName("toString은 서명 키를 가린다")
    void masksSecretInToString() {
        // given
        AccessTokenProperties properties = new AccessTokenProperties(Duration.ofMinutes(15), "grab", "grab-api", SECRET);

        // when
        String text = properties.toString();

        // then
        assertThat(text).doesNotContain(SECRET).contains("secret=masked", "grab-api");
    }

    // 실제 설정 바인딩 경로: yml 문자열(15m 등)이 record로 변환되고, 잘못된 값이면 컨텍스트가 뜨지 않는다
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("grab.auth.access-token 설정을 바인딩한다")
    void bindsFromProperties() {
        contextRunner
                .withPropertyValues(
                        "grab.auth.access-token.ttl=15m",
                        "grab.auth.access-token.issuer=grab",
                        "grab.auth.access-token.audience=grab-api",
                        "grab.auth.access-token.secret=" + SECRET)
                .run(context -> {
                    AccessTokenProperties properties = context.getBean(AccessTokenProperties.class);
                    assertThat(properties.ttl()).isEqualTo(Duration.ofMinutes(15));
                    assertThat(properties.issuer()).isEqualTo("grab");
                    assertThat(properties.audience()).isEqualTo("grab-api");
                });
    }

    @Test
    @DisplayName("서명 키 설정이 없으면 컨텍스트가 뜨지 않는다")
    void failsToStartWithoutSecret() {
        contextRunner
                .withPropertyValues(
                        "grab.auth.access-token.ttl=15m",
                        "grab.auth.access-token.issuer=grab",
                        "grab.auth.access-token.audience=grab-api")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AccessTokenProperties.class)
    static class PropertiesConfig {
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
