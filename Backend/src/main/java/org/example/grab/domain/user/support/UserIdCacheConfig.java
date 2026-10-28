package org.example.grab.domain.user.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/*
    인증 요청의 회원 ID 변환(public_id → users.id) 캐시(GR-211 M00-06).
    서비스(UserIdLookupService)와 지표 등록이 같은 캐시 빈을 주입받는다.
    대응이 바뀌지 않아 만료 없이 최대 항목 수로만 제한하고(M00-03), 적중·누락 지표를 위해 통계를 켠다(M00-05).
    global은 도메인을 참조하지 않으므로 user 도메인 안에서 설정을 등록한다(EmailVerificationConfig와 같은 방식)
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserIdCacheProperties.class)
public class UserIdCacheConfig {

    // 지표의 cache 태그 값. Prometheus에서 cache_gets_total{cache="user-id",result="hit|miss"} 등으로 나온다
    public static final String CACHE_NAME = "user-id";

    @Bean
    public Cache<UUID, Long> userIdCache(UserIdCacheProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(properties.maxSize()) // 최대 캐시 항목은 10만개
                .recordStats() // 적중, 누락 등의 통계 수집을 활성화
                .build();
    }

    /*
        적중·누락·저장·제거 수와 항목 수 지표(M00-05). Spring Cache 추상화를 쓰지 않아 Boot의 캐시 지표 자동 등록이
        이 캐시를 모르므로, Micrometer 표준 바인더(CaffeineCacheMetrics)를 MeterBinder 빈으로 직접 등록한다.
     */
    @Bean
    public MeterBinder userIdCacheMetrics(Cache<UUID, Long> userIdCache) {
        return new CaffeineCacheMetrics<>(userIdCache, CACHE_NAME, Tags.empty());
    }
}
