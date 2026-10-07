package org.example.grab.domain.wish.service;

import org.example.grab.domain.wish.dto.WishListProjection;
import org.example.grab.domain.wish.dto.response.WishListResponse;
import org.example.grab.domain.wish.repository.WishRepository;
import org.example.grab.global.common.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class WishQueryServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private WishRepository wishRepository;

    @InjectMocks
    private WishQueryService wishQueryService;

    @Test
    @DisplayName("내 WISH 목록은 프로젝션을 응답으로 변환하고 페이지 정보를 그대로 전달한다")
    void findMyWishes_mapsProjectionAndPage() {
        // given
        WishListProjection projection = projection(100L, "WISH");
        Page<WishListProjection> page = new PageImpl<>(
                List.of(projection), PageRequest.of(0, 20), 1);
        given(wishRepository.findActiveWishes(eq(USER_ID), any(Pageable.class))).willReturn(page);

        // when
        PageResponse<WishListResponse> response = wishQueryService.findMyWishes(USER_ID, 0, 20);

        // then
        assertThat(response.content()).hasSize(1);
        WishListResponse item = response.content().get(0);
        assertThat(item.dropId()).isEqualTo(100L);
        assertThat(item.name()).isEqualTo("상품");
        assertThat(item.thumbnailUrl()).isEqualTo("https://img/1.jpg");
        assertThat(item.minPrice()).isEqualTo(129000L);
        assertThat(item.status().name()).isEqualTo("WISH");
        assertThat(item.wishedAt()).isNotNull();
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();
        then(wishRepository).should().findActiveWishes(USER_ID, PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("활성 WISH가 없으면 빈 content를 반환한다")
    void findMyWishes_empty() {
        // given
        given(wishRepository.findActiveWishes(eq(USER_ID), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        // when
        PageResponse<WishListResponse> response = wishQueryService.findMyWishes(USER_ID, 0, 20);

        // then
        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    private WishListProjection projection(Long dropId, String status) {
        return new WishListProjection() {
            @Override
            public Long getDropId() {
                return dropId;
            }

            @Override
            public String getName() {
                return "상품";
            }

            @Override
            public String getThumbnailUrl() {
                return "https://img/1.jpg";
            }

            @Override
            public Long getMinPrice() {
                return 129000L;
            }

            @Override
            public String getStatus() {
                return status;
            }

            @Override
            public Instant getWishedAt() {
                return Instant.parse("2026-09-18T05:00:00Z");
            }
        };
    }
}
