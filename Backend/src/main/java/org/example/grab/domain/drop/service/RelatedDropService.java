package org.example.grab.domain.drop.service;

import org.example.grab.domain.drop.cache.config.DropCacheProperties;
import org.example.grab.domain.drop.cache.service.PublicDropCache;
import org.example.grab.domain.drop.dto.PublicDropListProjection;
import org.example.grab.domain.drop.dto.response.PublicDropListResponse;
import org.example.grab.domain.drop.dto.response.RelatedDropsResponse;
import org.example.grab.domain.drop.dto.response.RelatedDropsResponse.RelatedBasis;
import org.example.grab.domain.drop.entity.Drop;
import org.example.grab.domain.drop.error.DropErrorCode;
import org.example.grab.domain.drop.repository.DropRepository;
import org.example.grab.domain.drop.repository.RelatedDropRepository;
import org.example.grab.global.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/*
    관련 DROP 추천(DROP.md 1.4, DROP-011). 한정 굿즈는 팬층이 겹치는지가 중요해 함께 WISH를 먼저 쓰고,
    없을 때만 같은 카테고리에서 내용이 비슷한 DROP(임베딩)을 쓴다. 두 방식을 한 응답에 섞지 않아
    화면 문구가 추천 방식과 항상 일치한다. 판매 중이라 보여 주지 않을지는 화면이 정한다.
    결과는 사용자와 무관해 GR-183 응답 캐시에 related-ttl만큼 두고, 꺼낼 때마다 판매가 시작된 후보를 뺀다.
    캐시를 맞히면 DB를 쓰지 않도록 트랜잭션을 열지 않는다(PublicDropReadService와 같음).
 */
@Service
public class RelatedDropService {

    static final int LIMIT = 4;

    private final DropRepository dropRepository;
    private final RelatedDropRepository relatedDropRepository;
    private final PublicDropCache cache;
    private final DropCacheProperties cacheProperties;
    private final int minCoWishers;
    private final double similarityMinScore;
    private final Clock clock;

    @Autowired
    public RelatedDropService(
            DropRepository dropRepository,
            RelatedDropRepository relatedDropRepository,
            PublicDropCache cache,
            DropCacheProperties cacheProperties,
            @Value("${grab.drop.related.min-co-wishers:3}") int minCoWishers,
            @Value("${grab.demand.similarity.min-score:0.75}") double similarityMinScore
    ) {
        this(dropRepository, relatedDropRepository, cache, cacheProperties, minCoWishers, similarityMinScore,
                Clock.systemUTC());
    }

    RelatedDropService(
            DropRepository dropRepository,
            RelatedDropRepository relatedDropRepository,
            PublicDropCache cache,
            DropCacheProperties cacheProperties,
            int minCoWishers,
            double similarityMinScore,
            Clock clock
    ) {
        this.dropRepository = dropRepository;
        this.relatedDropRepository = relatedDropRepository;
        this.cache = cache;
        this.cacheProperties = cacheProperties;
        this.minCoWishers = minCoWishers;
        this.similarityMinScore = similarityMinScore;
        this.clock = clock;
    }

    // store=none이면 캐시와 동시 조회 합치기를 끄고 바로 계산한다. 실패(DROP_NOT_FOUND)는 저장하지 않는다
    public RelatedDropsResponse findRelated(Long dropId) {
        if (cacheProperties.store() == DropCacheProperties.Store.NONE) {
            return load(dropId);
        }
        RelatedDropsResponse cached = cache.get(PublicDropCache.relatedKey(dropId), dropId,
                RelatedDropsResponse.class, cacheProperties.relatedTtl(), () -> load(dropId));
        return withoutStarted(cached, OffsetDateTime.now(clock));
    }

    // 공개 상세와 같이 DRAFT는 존재를 숨긴다
    private RelatedDropsResponse load(Long dropId) {
        Drop drop = dropRepository.findById(dropId)
                .orElseThrow(() -> new BusinessException(DropErrorCode.DROP_NOT_FOUND));
        if (!drop.isPublic()) {
            throw new BusinessException(DropErrorCode.DROP_NOT_FOUND);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Long> coWished = relatedDropRepository.findCoWishedDropIds(dropId, now, minCoWishers, LIMIT);
        if (!coWished.isEmpty()) {
            return new RelatedDropsResponse(RelatedBasis.CO_WISH, cards(coWished));
        }
        List<Long> similar = relatedDropRepository.findSimilarDropIds(dropId, now, 1 - similarityMinScore, LIMIT);
        if (!similar.isEmpty()) {
            return new RelatedDropsResponse(RelatedBasis.SIMILAR, cards(similar));
        }
        return RelatedDropsResponse.empty();
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

    // 후보 순서를 지켜 목록 항목과 같은 카드로 만든다
    private List<PublicDropListResponse> cards(List<Long> ids) {
        Map<Long, PublicDropListProjection> byId = dropRepository.findPublicDropsByIds(ids).stream()
                .collect(Collectors.toMap(PublicDropListProjection::getDropId, Function.identity()));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(PublicDropListResponse::from)
                .toList();
    }
}
