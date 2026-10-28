package org.example.grab.domain.drop.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.cache.config.DropCacheProperties;
import org.example.grab.domain.drop.cache.service.PublicDropCache;
import org.example.grab.domain.drop.dto.response.PublicDropDetailResponse;
import org.example.grab.domain.drop.dto.response.PublicDropStockResponse;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 공개 DROP 상세·재고 조회의 캐시 계층(GR-183). SSE 알림마다 다시 불리는 두 경로만 다룬다.
 * 트랜잭션을 열지 않는다. 캐시를 맞히면 DB 커넥션을 쓰지 않고, 빗나갈 때만 DropService(읽기 전용 트랜잭션)를 부른다.
 * 응답의 actions·serverTime은 조회 시각에 따라 달라지므로 캐시한 응답을 꺼낼 때마다 now로 다시 계산한다.
 */
@Service
@RequiredArgsConstructor
public class PublicDropReadService {

    private final DropService dropService;
    private final PublicDropCache cache;
    private final DropCacheProperties properties;

    // store=none이면 저장뿐 아니라 동시 조회 합치기까지 끄고 DropService를 그대로 부른다(장애 시 끄기 스위치).
    public PublicDropDetailResponse findPublicDrop(Long dropId) {
        if (properties.store() == DropCacheProperties.Store.NONE) {
            return dropService.findPublicDrop(dropId);
        }
        PublicDropDetailResponse response = cache.get(PublicDropCache.detailKey(dropId), dropId,
                PublicDropDetailResponse.class, properties.detailTtl(), () -> dropService.findPublicDrop(dropId));
        return response.at(OffsetDateTime.now(ZoneOffset.UTC));
    }

    public PublicDropStockResponse findPublicStocks(Long dropId) {
        if (properties.store() == DropCacheProperties.Store.NONE) {
            return dropService.findPublicStocks(dropId);
        }
        PublicDropStockResponse response = cache.get(PublicDropCache.stockKey(dropId), dropId,
                PublicDropStockResponse.class, properties.stockTtl(), () -> dropService.findPublicStocks(dropId));
        return response.at(OffsetDateTime.now(ZoneOffset.UTC));
    }
}
