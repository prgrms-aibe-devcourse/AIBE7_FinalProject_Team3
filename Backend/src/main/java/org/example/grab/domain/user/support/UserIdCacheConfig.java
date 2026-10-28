package org.example.grab.domain.user.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

    @Bean
    public Cache<UUID, Long> userIdCache(UserIdCacheProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(properties.maxSize()) // 최대 캐시 항목은 10만개
                .recordStats() // 적중, 누락 등의 통계 수집을 활성화
                .build();
    }
}
