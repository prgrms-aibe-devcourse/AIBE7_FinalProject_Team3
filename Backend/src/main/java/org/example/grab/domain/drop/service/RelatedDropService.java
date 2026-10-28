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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    응답 캐시는 RelatedDropReadService가 맡고, 이 서비스는 캐시가 빗나갈 때만 불린다.
 */
@Service
public class RelatedDropService {

    static final int LIMIT = 4;

    private final DropRepository dropRepository;
    private final RelatedDropRepository relatedDropRepository;
    private final int minCoWishers;
    private final double similarityMinScore;
    private final Clock clock;

    @Autowired
    public RelatedDropService(
            DropRepository dropRepository,
            RelatedDropRepository relatedDropRepository,
            @Value("${grab.drop.related.min-co-wishers:3}") int minCoWishers,
            @Value("${grab.drop.related.similarity-min-score:0.75}") double similarityMinScore
    ) {
        this(dropRepository, relatedDropRepository, minCoWishers, similarityMinScore, Clock.systemUTC());
    }

    RelatedDropService(
            DropRepository dropRepository,
            RelatedDropRepository relatedDropRepository,
            int minCoWishers,
            double similarityMinScore,
            Clock clock
    ) {
        this.dropRepository = dropRepository;
        this.relatedDropRepository = relatedDropRepository;
        this.minCoWishers = minCoWishers;
        this.similarityMinScore = similarityMinScore;
        this.clock = clock;
    }

    // 공개 상세와 같이 DRAFT는 존재를 숨긴다
    @Transactional(readOnly = true)
    public RelatedDropsResponse findRelated(Long dropId) {
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
