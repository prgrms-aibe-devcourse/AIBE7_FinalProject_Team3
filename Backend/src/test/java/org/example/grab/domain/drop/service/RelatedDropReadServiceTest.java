package org.example.grab.domain.drop.service;

import org.example.grab.domain.drop.cache.config.DropCacheProperties;
import org.example.grab.domain.drop.cache.config.DropCacheProperties.Store;
import org.example.grab.domain.drop.cache.service.PublicDropCache;
import org.example.grab.domain.drop.cache.store.InMemoryDropCacheStore;
import org.example.grab.domain.drop.dto.response.PublicDropListResponse;
import org.example.grab.domain.drop.dto.response.RelatedDropsResponse;
import org.example.grab.domain.drop.dto.response.RelatedDropsResponse.RelatedBasis;
import org.example.grab.domain.drop.dto.response.common.DropCategoryResponse;
import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RelatedDropReadServiceTest {

    private static final long DROP_ID = 100L;
    private static final Instant NOW = Instant.parse("2026-10-27T05:00:00Z");

    private RelatedDropService relatedDropService;
    private PublicDropCache cache;

    @BeforeEach
    void setUp() {
        relatedDropService = mock(RelatedDropService.class);
        cache = new PublicDropCache(new InMemoryDropCacheStore());
    }

    @Test
    @DisplayName("결과는 캐시해 같은 DROP을 다시 조회해도 다시 계산하지 않고, 실패는 저장하지 않는다")
    void findRelated_cachesResult() {
        // given
        RelatedDropsResponse response = new RelatedDropsResponse(RelatedBasis.SIMILAR,
                List.of(card(103L, NOW.plus(Duration.ofDays(1)))));
        given(relatedDropService.findRelated(DROP_ID)).willReturn(response);
        given(relatedDropService.findRelated(8L)).willThrow(new BusinessException(DropErrorCode.DROP_NOT_FOUND));
        RelatedDropReadService service = service(Store.MEMORY, NOW);

        // when
        RelatedDropsResponse first = service.findRelated(DROP_ID);
        RelatedDropsResponse second = service.findRelated(DROP_ID);

        // then: 없는 DROP은 매번 확인한다
        assertThat(first).isEqualTo(response);
        assertThat(second).isEqualTo(response);
        verify(relatedDropService, times(1)).findRelated(DROP_ID);
        assertThatThrownBy(() -> service.findRelated(8L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.findRelated(8L)).isInstanceOf(BusinessException.class);
        verify(relatedDropService, times(2)).findRelated(8L);
    }

    @Test
    @DisplayName("캐시에 있는 동안 판매가 시작된 후보는 꺼낼 때 빼고, 모두 빠지면 추천 없음이다")
    void findRelated_dropsStartedCandidates() {
        // given: 103은 하루 뒤, 104는 1시간 뒤 판매 시작
        given(relatedDropService.findRelated(DROP_ID)).willReturn(new RelatedDropsResponse(RelatedBasis.CO_WISH,
                List.of(card(104L, NOW.plus(Duration.ofHours(1))), card(103L, NOW.plus(Duration.ofDays(1))))));
        service(Store.MEMORY, NOW).findRelated(DROP_ID);

        // when: 같은 캐시를 2시간 뒤·이틀 뒤에 꺼낸다
        RelatedDropsResponse later = service(Store.MEMORY, NOW.plus(Duration.ofHours(2))).findRelated(DROP_ID);
        RelatedDropsResponse muchLater = service(Store.MEMORY, NOW.plus(Duration.ofDays(2))).findRelated(DROP_ID);

        // then
        assertThat(later.basis()).isEqualTo(RelatedBasis.CO_WISH);
        assertThat(later.drops()).extracting(PublicDropListResponse::dropId).containsExactly(103L);
        assertThat(muchLater).isEqualTo(RelatedDropsResponse.empty());
        verify(relatedDropService, times(1)).findRelated(DROP_ID);
    }

    @Test
    @DisplayName("캐시 저장소가 none이면 매번 계산한다")
    void findRelated_bypassesCacheWhenDisabled() {
        given(relatedDropService.findRelated(DROP_ID)).willReturn(RelatedDropsResponse.empty());
        RelatedDropReadService uncached = service(Store.NONE, NOW);

        uncached.findRelated(DROP_ID);
        uncached.findRelated(DROP_ID);

        verify(relatedDropService, times(2)).findRelated(DROP_ID);
    }

    private RelatedDropReadService service(Store store, Instant now) {
        DropCacheProperties properties = new DropCacheProperties(store, Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofMinutes(3));
        return new RelatedDropReadService(relatedDropService, cache, properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static PublicDropListResponse card(long dropId, Instant saleStartsAt) {
        OffsetDateTime startsAt = saleStartsAt.atOffset(ZoneOffset.UTC);
        return new PublicDropListResponse(dropId, "상품 " + dropId, "브랜드", null, 15000L,
                new DropCategoryResponse(8L, "취미·굿즈"), DropStatus.WISH, false, 10L, 3L, startsAt,
                startsAt.plusHours(2));
    }
}
