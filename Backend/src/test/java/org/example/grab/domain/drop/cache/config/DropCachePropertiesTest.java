package org.example.grab.domain.drop.cache.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class DropCachePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DropCacheProperties.class)
    static class Config {
    }

    @Test
    @DisplayName("기본 최대 항목 수는 1000이고 TTL과 저장소 기본값은 유지된다")
    void bindsDefaults() {
        // given / when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            DropCacheProperties properties = context.getBean(DropCacheProperties.class);
            assertThat(properties.maxSize()).isEqualTo(1000);
            assertThat(properties.store()).isEqualTo(DropCacheProperties.Store.MEMORY);
            assertThat(properties.detailTtl()).hasSeconds(5);
            assertThat(properties.stockTtl()).hasSeconds(5);
            assertThat(properties.relatedTtl()).hasMinutes(3);
        });
    }

    @Test
    @DisplayName("배포 설정으로 최대 항목 수를 재정의하며 1도 허용한다")
    void overridesMaxSize() {
        // given / when
        runner.withPropertyValues("grab.drop-cache.max-size=1").run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DropCacheProperties.class).maxSize()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("최대 항목 수가 0 이하이면 기동을 거부한다")
    void rejectsInvalidMaxSize() {
        // given / when / then
        for (long size : new long[]{0, -1}) {
            runner.withPropertyValues("grab.drop-cache.max-size=" + size)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
