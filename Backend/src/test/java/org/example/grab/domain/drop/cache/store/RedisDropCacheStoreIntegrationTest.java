package org.example.grab.domain.drop.cache.store;

import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse;
import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse.Actions;
import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse.Image;
import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse.Option;
import org.example.grab.domain.drop.dto.response.PublicDropListResponse;
import org.example.grab.domain.drop.dto.response.PublicDropStockResponse;
import org.example.grab.domain.drop.dto.response.RelatedDropsResponse;
import org.example.grab.domain.drop.dto.response.RelatedDropsResponse.RelatedBasis;
import org.example.grab.domain.drop.dto.response.common.DropCategoryResponse;
import org.example.grab.domain.drop.dto.response.common.DropOptionGroupResponse;
import org.example.grab.domain.drop.dto.response.common.DropOptionSelectionResponse;
import org.example.grab.domain.drop.dto.response.common.DropShippingResponse;
import org.example.grab.domain.drop.entity.DropStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * Redis 저장소가 공개 상세·재고·관련 DROP 추천 응답을 JSON으로 저장했다가 같은 값으로 되돌리는지 확인한다(GR-183).
 * 앱과 같은 ObjectMapper(Jackson 자동 설정)를 쓴다.
 */
@DataRedisTest
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Testcontainers
class RedisDropCacheStoreIntegrationTest {

    private static final OffsetDateTime STARTS = OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    @DisplayName("공개 상세 응답을 저장했다가 같은 값으로 읽는다")
    void roundTripsDetail() {
        // given
        RedisDropCacheStore store = new RedisDropCacheStore(redisTemplate, objectMapper);
        PublicDropDetailResponse response = new PublicDropDetailResponse(1L, "상품", "브랜드", "설명",
                List.of("https://img/1.jpg"), List.of(new Image("https://img/1.jpg", "대표")),
                List.of(new Image("https://img/d.jpg", "상세")), 1000L, new DropCategoryResponse(2L, "패션"),
                DropStatus.GRAB, false, 5L, "안내", STARTS, STARTS.plusDays(1), new DropShippingResponse(3000L, "배송"),
                List.of(new DropOptionGroupResponse(3L, "색상", 0, List.of(new DropOptionGroupResponse.Value(4L, "블랙", 0)))),
                List.of(new Option(5L, List.of(new DropOptionSelectionResponse(3L, 4L)), 1000L, 7, false)),
                new Actions(false, false, true), STARTS.plusHours(1));

        // when
        store.put("detail", response, Duration.ofMinutes(1));
        PublicDropDetailResponse read = store.get("detail", PublicDropDetailResponse.class);

        // then: 시각은 같은 순간이면 된다(직렬화 뒤 오프셋 표기만 다를 수 있다)
        assertThat(read).usingRecursiveComparison()
                .withComparatorForType(OffsetDateTime::compareTo, OffsetDateTime.class)
                .isEqualTo(response);
    }

    @Test
    @DisplayName("공개 재고 응답을 저장했다가 같은 값으로 읽고, 지우면 없다")
    void roundTripsStockAndEvicts() {
        // given
        RedisDropCacheStore store = new RedisDropCacheStore(redisTemplate, objectMapper);
        PublicDropStockResponse response = new PublicDropStockResponse(1L,
                List.of(new PublicDropStockResponse.Option(5L, 0, true)), STARTS);
        store.put("stock", response, Duration.ofMinutes(1));

        // when & then
        assertThat(store.get("stock", PublicDropStockResponse.class)).usingRecursiveComparison()
                .withComparatorForType(OffsetDateTime::compareTo, OffsetDateTime.class)
                .isEqualTo(response);
        store.evict("stock");
        assertThat(store.get("stock", PublicDropStockResponse.class)).isNull();
    }

    @Test
    @DisplayName("관련 DROP 추천 응답(GR-195)을 저장했다가 같은 값으로 읽고, 추천 없음(basis null)도 그대로 읽는다")
    void roundTripsRelated() {
        // given
        RedisDropCacheStore store = new RedisDropCacheStore(redisTemplate, objectMapper);
        RelatedDropsResponse response = new RelatedDropsResponse(RelatedBasis.CO_WISH, List.of(
                new PublicDropListResponse(101L, "상품", "브랜드", null, 15000L, new DropCategoryResponse(8L, "취미·굿즈"),
                        DropStatus.WISH, false, 50L, 48L, STARTS, STARTS.plusHours(2))));
        store.put("related", response, Duration.ofMinutes(1));
        store.put("empty", RelatedDropsResponse.empty(), Duration.ofMinutes(1));

        // when & then
        assertThat(store.get("related", RelatedDropsResponse.class)).usingRecursiveComparison()
                .withComparatorForType(OffsetDateTime::compareTo, OffsetDateTime.class)
                .isEqualTo(response);
        assertThat(store.get("empty", RelatedDropsResponse.class)).isEqualTo(RelatedDropsResponse.empty());
    }
}
