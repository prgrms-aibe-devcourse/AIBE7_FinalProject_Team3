package org.example.grab.domain.drop.service;

import org.example.grab.domain.drop.cache.config.DropCacheProperties;
import org.example.grab.domain.drop.cache.service.PublicDropCache;
import org.example.grab.domain.drop.dto.response.PublicDropListResponse;
import org.example.grab.domain.drop.dto.response.RelatedDropsResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관련 DROP 추천의 캐시 계층(GR-195). 결과는 사용자와 무관해 GR-183 응답 캐시에 related-ttl만큼 둔다.
 * 트랜잭션을 열지 않는다. 캐시를 맞히면 DB 커넥션을 쓰지 않고, 빗나갈 때만 RelatedDropService(읽기 전용 트랜잭션)를 부른다
 * (PublicDropReadService와 같은 구조). 후보 DROP의 변경으로는 지우지 않으므로, 꺼낼 때마다 판매가 시작된 후보를 뺀다.
 */
@Service
public class RelatedDropReadService {

    private final RelatedDropService relatedDropService;
    private final PublicDropCache cache;
    private final DropCacheProperties properties;
    private final Clock clock;

    @Autowired
    public RelatedDropReadService(RelatedDropService relatedDropService, PublicDropCache cache,
                                  DropCacheProperties properties) {
        this(relatedDropService, cache, properties, Clock.systemUTC());
    }

    RelatedDropReadService(RelatedDropService relatedDropService, PublicDropCache cache,
                           DropCacheProperties properties, Clock clock) {
        this.relatedDropService = relatedDropService;
        this.cache = cache;
        this.properties = properties;
        this.clock = clock;
    }

    // store=none이면 캐시와 동시 조회 합치기를 끄고 바로 계산한다. 실패(DROP_NOT_FOUND)는 저장하지 않는다
    public RelatedDropsResponse findRelated(Long dropId) {
        if (properties.store() == DropCacheProperties.Store.NONE) {
            return relatedDropService.findRelated(dropId);
        }
        RelatedDropsResponse cached = cache.get(PublicDropCache.relatedKey(dropId), dropId,
                RelatedDropsResponse.class, properties.relatedTtl(), () -> relatedDropService.findRelated(dropId));
        return withoutStarted(cached, OffsetDateTime.now(clock));
    }

    // 캐시에 있는 동안 판매가 시작된 후보는 더 이상 WISH할 수 없어 뺀다. 모두 빠지면 추천 없음이다
    private static RelatedDropsResponse withoutStarted(RelatedDropsResponse response, OffsetDateTime now) {
        List<PublicDropListResponse> upcoming = response.drops().stream()
                .filter(drop -> drop.saleStartsAt() != null && drop.saleStartsAt().isAfter(now))
                .toList();
        if (upcoming.size() == response.drops().size()) {
            return response;
        }
        return upcoming.isEmpty() ? RelatedDropsResponse.empty() : new RelatedDropsResponse(response.basis(), upcoming);
    }
}
