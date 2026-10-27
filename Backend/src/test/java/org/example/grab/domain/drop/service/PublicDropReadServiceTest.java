package org.example.grab.domain.drop.service;

import org.example.grab.domain.drop.cache.config.DropCacheProperties;
import org.example.grab.domain.drop.cache.service.PublicDropCache;
import org.example.grab.domain.drop.cache.store.InMemoryDropCacheStore;
import org.example.grab.domain.drop.dto.response.PublicDropStockResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PublicDropReadServiceTest {

    private static final OffsetDateTime LOADED_AT = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("캐시를 켜면 같은 재고 조회는 DropService를 한 번만 부르고, serverTime은 조회 시각으로 바꾼다")
    void cachesStocksAndRefreshesServerTime() {
        // given
        DropService dropService = mock(DropService.class);
        given(dropService.findPublicStocks(1L)).willReturn(stock());
        PublicDropReadService service = service(dropService, DropCacheProperties.Store.MEMORY);

        // when
        service.findPublicStocks(1L);
        PublicDropStockResponse second = service.findPublicStocks(1L);

        // then
        verify(dropService, times(1)).findPublicStocks(1L);
        assertThat(second.serverTime()).isAfter(LOADED_AT);
    }

    @Test
    @DisplayName("store=none이면 캐시 계층을 건너뛰고 매번 DropService를 부른다")
    void bypassesCacheWhenNone() {
        // given
        DropService dropService = mock(DropService.class);
        given(dropService.findPublicStocks(1L)).willReturn(stock());
        PublicDropReadService service = service(dropService, DropCacheProperties.Store.NONE);

        // when
        service.findPublicStocks(1L);
        service.findPublicStocks(1L);

        // then
        verify(dropService, times(2)).findPublicStocks(1L);
    }

    private static PublicDropReadService service(DropService dropService, DropCacheProperties.Store store) {
        DropCacheProperties properties = new DropCacheProperties(store, Duration.ofSeconds(5), Duration.ofSeconds(5));
        return new PublicDropReadService(dropService, new PublicDropCache(new InMemoryDropCacheStore()), properties);
    }

    private static PublicDropStockResponse stock() {
        return new PublicDropStockResponse(1L, List.of(new PublicDropStockResponse.Option(10L, 3, false)), LOADED_AT);
    }
}
