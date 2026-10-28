package org.example.grab.domain.drop.service;

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
import static org.mockito.Mockito.verify;

class RelatedDropServiceTest {

    private static final long DROP_ID = 100L;
    private static final Instant NOW = Instant.parse("2026-10-27T05:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private DropRepository dropRepository;
    private RelatedDropRepository relatedDropRepository;
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
        service = new RelatedDropService(dropRepository, relatedDropRepository, 3, 0.75, Clock.fixed(NOW, ZoneOffset.UTC));
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

    private static PublicDropListProjection projection(long dropId) {
        PublicDropListProjection projection = mock(PublicDropListProjection.class);
        given(projection.getDropId()).willReturn(dropId);
        given(projection.getName()).willReturn("상품 " + dropId);
        given(projection.getStatus()).willReturn("WISH");
        given(projection.getCategoryId()).willReturn(8L);
        given(projection.getCategoryName()).willReturn("취미·굿즈");
        return projection;
    }
}
