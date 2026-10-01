package org.example.grab.domain.auth.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// GR-33 M01-04: Refresh Token 유효기간 설정 검증과 바인딩
class RefreshTokenPropertiesTest {

    private static final Duration IDLE = Duration.ofDays(14);
    private static final Duration ABSOLUTE = Duration.ofDays(30);

    @Test
    @DisplayName("올바른 값이면 그대로 담는다")
    void holdsValidValues() {
        // when
        RefreshTokenProperties properties = new RefreshTokenProperties(IDLE, ABSOLUTE);

        // then
        assertThat(properties.idleTtl()).isEqualTo(IDLE);
        assertThat(properties.absoluteTtl()).isEqualTo(ABSOLUTE);
    }

    @Test
    @DisplayName("idle-ttl이 없거나 0 이하이면 거부한다")
    void rejectsNonPositiveIdleTtl() {
        assertRejected(null, ABSOLUTE, "idle-ttl");
        assertRejected(Duration.ZERO, ABSOLUTE, "idle-ttl");
        assertRejected(Duration.ofDays(-1), ABSOLUTE, "idle-ttl");
    }

    @Test
    @DisplayName("absolute-ttl이 없거나 0 이하이면 거부한다")
    void rejectsNonPositiveAbsoluteTtl() {
        assertRejected(IDLE, null, "absolute-ttl");
        assertRejected(IDLE, Duration.ZERO, "absolute-ttl");
        assertRejected(IDLE, Duration.ofDays(-1), "absolute-ttl");
    }

    @Test
    @DisplayName("idle-ttl이 absolute-ttl보다 길면 거부한다(두 값을 바꿔 적은 경우)")
    void rejectsIdleLongerThanAbsolute() {
        assertRejected(ABSOLUTE, IDLE, "idle-ttl");
        assertRejected(ABSOLUTE.plusSeconds(1), ABSOLUTE, "idle-ttl");
    }

    @Test
    @DisplayName("두 값이 같으면 비활동 만료 없는 고정 기간으로 허용한다")
    void allowsEqualValues() {
        RefreshTokenProperties properties = new RefreshTokenProperties(ABSOLUTE, ABSOLUTE);

        assertThat(properties.idleTtl()).isEqualTo(properties.absoluteTtl());
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RefreshTokenConfig.class);

    @Test
    @DisplayName("grab.auth.refresh-token 설정을 일 단위 표기(14d·30d)로 바인딩한다")
    void bindsFromProperties() {
        contextRunner
                .withPropertyValues(
                        "grab.auth.refresh-token.idle-ttl=14d",
                        "grab.auth.refresh-token.absolute-ttl=30d")
                .run(context -> {
                    RefreshTokenProperties properties = context.getBean(RefreshTokenProperties.class);
                    assertThat(properties.idleTtl()).isEqualTo(IDLE);
                    assertThat(properties.absoluteTtl()).isEqualTo(ABSOLUTE);
                });
    }

    @Test
    @DisplayName("설정이 없거나 idle-ttl이 더 길면 컨텍스트가 뜨지 않는다")
    void failsToStartWithInvalidProperties() {
        contextRunner
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("grab.auth.refresh-token.idle-ttl=14d")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues(
                        "grab.auth.refresh-token.idle-ttl=30d",
                        "grab.auth.refresh-token.absolute-ttl=14d")
                .run(context -> assertThat(context).hasFailed());
    }

    private static void assertRejected(Duration idleTtl, Duration absoluteTtl, String name) {
        assertThatThrownBy(() -> new RefreshTokenProperties(idleTtl, absoluteTtl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(name);
    }
}
