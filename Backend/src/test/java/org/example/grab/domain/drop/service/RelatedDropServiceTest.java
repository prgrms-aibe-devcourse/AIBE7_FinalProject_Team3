package org.example.grab.domain.drop.service;

import org.example.grab.domain.drop.cache.config.DropCacheProperties;
import org.example.grab.domain.drop.cache.config.DropCacheProperties.Store;
import org.example.grab.domain.drop.cache.service.PublicDropCache;
import org.example.grab.domain.drop.cache.store.InMemoryDropCacheStore;
import org.example.grab.domain.drop.dto.PublicDropListProjection;
import org.example.grab.domain.drop.dto.response.PublicDropListResponse;
import org.example.grab.domain.drop.dto.response.RelatedDropsResponse;
import org.example.grab.domain.drop.dto.response.RelatedDropsResponse.RelatedBasis;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.domain.drop.repository.RelatedDropRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RelatedDropServiceTest {

    private static final long DROP_ID = 100L;
    private static final Instant NOW = Instant.parse("2026-10-27T05:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private DropRepository dropRepository;
    private RelatedDropRepository relatedDropRepository;
    private PublicDropCache cache;
    private RelatedDropService service;

    @BeforeEach
    void setUp() {
        dropRepository = mock(DropRepository.class);
        relatedDropRepository = mock(RelatedDropRepository.class);
        Drop drop = mock(Drop.class);
        given(drop.isPublic()).willReturn(true);
        given(dropRepository.findById(DROP_ID)).willReturn(Optional.of(drop));
        given(relatedDropRepository.findCoWishedDropIds(anyLong(), any(), anyInt(), anyInt())).willReturn(List.of());
        given(relatedDropRepository.findSimilarDropIds(anyLong(), any(), anyDouble(), anyInt())).willReturn(List.of());
        cache = new PublicDropCache(new InMemoryDropCacheStore());
        service = service(Store.MEMORY, NOW);
    }

    @Test
    @DisplayName("함께 WISH한 DROP이 있으면 CO_WISH로 그 순서대로 카드를 만들고 비슷한 DROP은 찾지 않는다")
    void findRelated_prefersCoWish() {
        // given: 저장소는 순서와 무관하게 돌려준다
        given(relatedDropRepository.findCoWishedDropIds(DROP_ID, NOW_UTC, 3, 4)).willReturn(List.of(102L, 101L));
        List<PublicDropListProjection> cards = List.of(projection(101L), projection(102L));
        given(dropRepository.findPublicDropsByIds(List.of(102L, 101L))).willReturn(cards);

        // when
        RelatedDropsResponse response = service.findRelated(DROP_ID);

        // then
        assertThat(response.basis()).isEqualTo(RelatedBasis.CO_WISH);
        assertThat(response.drops()).extracting(PublicDropListResponse::dropId).containsExactly(102L, 101L);
        verify(relatedDropRepository, never()).findSimilarDropIds(anyLong(), any(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("함께 WISH가 없으면 유사도 기준(1 - 0.75 거리)으로 비슷한 DROP을 SIMILAR로 반환한다")
    void findRelated_fallsBackToSimilar() {
        given(relatedDropRepository.findSimilarDropIds(DROP_ID, NOW_UTC, 0.25, 4)).willReturn(List.of(103L));
        List<PublicDropListProjection> cards = List.of(projection(103L));
        given(dropRepository.findPublicDropsByIds(List.of(103L))).willReturn(cards);

        RelatedDropsResponse response = service.findRelated(DROP_ID);

        assertThat(response.basis()).isEqualTo(RelatedBasis.SIMILAR);
        assertThat(response.drops()).extracting(PublicDropListResponse::dropId).containsExactly(103L);
    }

    @Test
    @DisplayName("둘 다 없으면 basis 없이 빈 목록이고, 없거나 공개되지 않은 DROP은 DROP_NOT_FOUND다")
    void findRelated_emptyOrHidden() {
        assertThat(service.findRelated(DROP_ID)).isEqualTo(RelatedDropsResponse.empty());

        Drop draft = mock(Drop.class);
        given(draft.isPublic()).willReturn(false);
        given(dropRepository.findById(7L)).willReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.findRelated(7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DropErrorCode.DROP_NOT_FOUND);
        assertThatThrownBy(() -> service.findRelated(8L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DropErrorCode.DROP_NOT_FOUND);
    }

    @Test
    @DisplayName("결과는 캐시해 같은 DROP을 다시 조회해도 DB를 다시 쓰지 않고, 실패는 저장하지 않는다")
    void findRelated_cachesResult() {
        given(relatedDropRepository.findSimilarDropIds(DROP_ID, NOW_UTC, 0.25, 4)).willReturn(List.of(103L));
        List<PublicDropListProjection> cards = List.of(projection(103L));
        given(dropRepository.findPublicDropsByIds(List.of(103L))).willReturn(cards);

        RelatedDropsResponse first = service.findRelated(DROP_ID);
        RelatedDropsResponse second = service.findRelated(DROP_ID);

        assertThat(second).isEqualTo(first);
        verify(dropRepository, times(1)).findById(DROP_ID);
        verify(relatedDropRepository, times(1)).findSimilarDropIds(anyLong(), any(), anyDouble(), anyInt());
        // 없는 DROP은 매번 DB에서 확인한다
        assertThatThrownBy(() -> service.findRelated(8L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.findRelated(8L)).isInstanceOf(BusinessException.class);
        verify(dropRepository, times(2)).findById(8L);
    }

    @Test
    @DisplayName("캐시에 있는 동안 판매가 시작된 후보는 꺼낼 때 빼고, 모두 빠지면 추천 없음이다")
    void findRelated_dropsStartedCandidates() {
        // given: 103은 하루 뒤, 104는 1시간 뒤 판매 시작
        given(relatedDropRepository.findCoWishedDropIds(DROP_ID, NOW_UTC, 3, 4)).willReturn(List.of(104L, 103L));
        List<PublicDropListProjection> cards = List.of(projection(103L), projection(104L, NOW.plus(Duration.ofHours(1))));
        given(dropRepository.findPublicDropsByIds(List.of(104L, 103L))).willReturn(cards);
        service.findRelated(DROP_ID);

        // when: 같은 캐시를 2시간 뒤·이틀 뒤에 꺼낸다
        RelatedDropsResponse later = service(Store.MEMORY, NOW.plus(Duration.ofHours(2))).findRelated(DROP_ID);
        RelatedDropsResponse muchLater = service(Store.MEMORY, NOW.plus(Duration.ofDays(2))).findRelated(DROP_ID);

        // then
        assertThat(later.basis()).isEqualTo(RelatedBasis.CO_WISH);
        assertThat(later.drops()).extracting(PublicDropListResponse::dropId).containsExactly(103L);
        assertThat(muchLater).isEqualTo(RelatedDropsResponse.empty());
        verify(dropRepository, times(1)).findById(DROP_ID);
    }

    @Test
    @DisplayName("캐시 저장소가 none이면 매번 계산한다")
    void findRelated_bypassesCacheWhenDisabled() {
        RelatedDropService uncached = service(Store.NONE, NOW);

        uncached.findRelated(DROP_ID);
        uncached.findRelated(DROP_ID);

        verify(dropRepository, times(2)).findById(DROP_ID);
    }

    private RelatedDropService service(Store store, Instant now) {
        DropCacheProperties properties = new DropCacheProperties(store, Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofMinutes(3));
        return new RelatedDropService(dropRepository, relatedDropRepository, cache, properties, 3, 0.75,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static PublicDropListProjection projection(long dropId) {
        return projection(dropId, NOW.plus(Duration.ofDays(1)));
    }

    private static PublicDropListProjection projection(long dropId, Instant saleStartsAt) {
        PublicDropListProjection projection = mock(PublicDropListProjection.class);
        given(projection.getDropId()).willReturn(dropId);
        given(projection.getName()).willReturn("상품 " + dropId);
        given(projection.getStatus()).willReturn("WISH");
        given(projection.getCategoryId()).willReturn(8L);
        given(projection.getCategoryName()).willReturn("취미·굿즈");
        given(projection.getSaleStartsAt()).willReturn(saleStartsAt);
        return projection;
    }
}
