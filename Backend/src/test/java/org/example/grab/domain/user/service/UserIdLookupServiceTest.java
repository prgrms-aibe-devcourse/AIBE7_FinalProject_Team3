package org.example.grab.domain.user.service;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.domain.user.support.UserIdCacheConfig;
import org.example.grab.domain.user.support.UserIdCacheProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

// GR-211 M03-01: 회원 ID 변환 캐시. 있는 회원만 저장하고 최대 항목 수를 지키며, 지표가 적중·누락을 그대로 보여준다(M00-02·05·06)
@ExtendWith(MockitoExtension.class)
class UserIdLookupServiceTest {

    private static final UUID PUBLIC_ID = UUID.fromString("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f");

    @Mock
    private UserRepository userRepository;

    private final UserIdCacheConfig config = new UserIdCacheConfig();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void tearDown() {
        meters.close();
    }

    // 운영과 같은 설정 클래스로 캐시와 지표를 만든다
    private Cache<UUID, Long> cache(long maxSize) {
        Cache<UUID, Long> cache = config.userIdCache(new UserIdCacheProperties(maxSize));
        config.userIdCacheMetrics(cache).bindTo(meters);
        return cache;
    }

    @Test
    @DisplayName("같은 public_id를 두 번 바꾸면 DB는 처음 한 번만 조회한다")
    void queriesDatabaseOnceForSameMember() {
        // given
        UserIdLookupService service = new UserIdLookupService(userRepository, cache(100));
        given(userRepository.findIdByPublicId(PUBLIC_ID)).willReturn(Optional.of(7L));

        // when
        Optional<Long> first = service.resolve(PUBLIC_ID);
        Optional<Long> second = service.resolve(PUBLIC_ID);

        // then
        assertThat(first).contains(7L);
        assertThat(second).contains(7L);
        then(userRepository).should(times(1)).findIdByPublicId(PUBLIC_ID);
    }

    @Test
    @DisplayName("없는 회원은 저장하지 않아 다음 호출도 DB를 조회한다")
    void doesNotCacheUnknownMember() {
        // given
        Cache<UUID, Long> cache = cache(100);
        UserIdLookupService service = new UserIdLookupService(userRepository, cache);
        given(userRepository.findIdByPublicId(PUBLIC_ID)).willReturn(Optional.empty());

        // when
        Optional<Long> first = service.resolve(PUBLIC_ID);
        Optional<Long> second = service.resolve(PUBLIC_ID);

        // then
        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        then(userRepository).should(times(2)).findIdByPublicId(PUBLIC_ID);
        assertThat(cache.asMap()).isEmpty();
    }

    @Test
    @DisplayName("최대 항목 수를 넘으면 덜 쓰는 항목을 버려 한도를 지킨다")
    void keepsMaximumSize() {
        // given: 최대 2개
        Cache<UUID, Long> cache = cache(2);
        UserIdLookupService service = new UserIdLookupService(userRepository, cache);
        for (long id = 1; id <= 3; id++) {
            UUID publicId = UUID.randomUUID();
            given(userRepository.findIdByPublicId(publicId)).willReturn(Optional.of(id));

            // when
            service.resolve(publicId);
        }
        // 제거는 비동기라 남은 정리 작업을 끝낸 뒤 본다
        cache.cleanUp();

        // then
        assertThat(cache.estimatedSize()).isEqualTo(2);
        assertThat(meters.get("cache.evictions").tag("cache", UserIdCacheConfig.CACHE_NAME).functionCounter().count()).isEqualTo(1);
        assertThat(meters.get("cache.size").tag("cache", UserIdCacheConfig.CACHE_NAME).gauge().value()).isEqualTo(2);
    }

    @Test
    @DisplayName("지표는 cache=user-id 태그로 적중·누락 수와 항목 수를 보여준다")
    void exposesHitMissAndSize() {
        // given
        UserIdLookupService service = new UserIdLookupService(userRepository, cache(100));
        UUID unknown = UUID.randomUUID();
        given(userRepository.findIdByPublicId(PUBLIC_ID)).willReturn(Optional.of(7L));
        given(userRepository.findIdByPublicId(unknown)).willReturn(Optional.empty());

        // when: 회원 첫 요청(누락) → 두 번 적중, 없는 회원 한 번(누락)
        service.resolve(PUBLIC_ID);
        service.resolve(PUBLIC_ID);
        service.resolve(PUBLIC_ID);
        service.resolve(unknown);

        // then
        assertThat(gets("hit")).isEqualTo(2);
        assertThat(gets("miss")).isEqualTo(2);
        assertThat(meters.get("cache.size").tag("cache", UserIdCacheConfig.CACHE_NAME).gauge().value()).isEqualTo(1);
        assertThat(meters.get("cache.evictions").tag("cache", UserIdCacheConfig.CACHE_NAME).functionCounter().count())
                .isZero();
        // cache.puts는 Caffeine의 로드 수를 읽는다. 로더 없이 직접 put하므로 0으로 남는다(누락 수·항목 수로 읽는다)
        assertThat(meters.get("cache.puts").tag("cache", UserIdCacheConfig.CACHE_NAME).functionCounter().count())
                .isZero();
    }

    private double gets(String result) {
        return meters.get("cache.gets").tags("cache", UserIdCacheConfig.CACHE_NAME, "result", result)
                .functionCounter().count();
    }
}
