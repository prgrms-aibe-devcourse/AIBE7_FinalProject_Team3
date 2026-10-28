package org.example.grab.domain.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// GR-211 M03-02: 회원 ID 변환 캐시 설정의 검증과 바인딩(M00-02)
class UserIdCachePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(UserIdCacheConfig.class);

    @Test
    @DisplayName("max-size가 1보다 작으면 거부하고 1은 허용한다")
    void validatesMaxSize() {
        assertRejected(0);
        assertRejected(-1);
        assertThat(new UserIdCacheProperties(1).maxSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("설정이 없으면 기본값 10만으로 바인딩하고 캐시 빈을 만든다")
    void bindsDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(UserIdCacheProperties.class).maxSize()).isEqualTo(100_000);
            assertThat(context).hasBean("userIdCache");
            assertThat(context).hasBean("userIdCacheMetrics");
        });
    }

    @Test
    @DisplayName("application.yml의 기본값은 10만이다")
    void bindsDefaultFromApplicationYml() {
        contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> assertThat(context.getBean(UserIdCacheProperties.class).maxSize())
                        .isEqualTo(100_000));
    }

    @Test
    @DisplayName("기본값을 배포 설정으로 덮어쓸 수 있다")
    void overridesDefault() {
        contextRunner
                .withPropertyValues("grab.user-id-cache.max-size=500")
                .run(context -> assertThat(context.getBean(UserIdCacheProperties.class).maxSize()).isEqualTo(500));
    }

    @Test
    @DisplayName("max-size가 범위를 벗어나면 컨텍스트가 뜨지 않는다")
    void failsToStartWithInvalidMaxSize() {
        contextRunner
                .withPropertyValues("grab.user-id-cache.max-size=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("grab.user-id-cache.max-size=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    private static void assertRejected(long maxSize) {
        assertThatThrownBy(() -> new UserIdCacheProperties(maxSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-size");
    }
}
