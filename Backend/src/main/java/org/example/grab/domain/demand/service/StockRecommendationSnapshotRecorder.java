package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.dto.StockRecommendationResponse;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.OptionRecommendation;
import org.example.grab.domain.demand.repository.DropEmbeddingRepository;
import org.example.grab.domain.demand.repository.StockRecommendationRepository;
import org.example.grab.domain.demand.repository.StockRecommendationRepository.PublishedDrop;
import org.example.grab.domain.demand.repository.StockRecommendationRepository.PublishedOption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/*
    공개 시점 재고 추천을 계산해 기록한다(DEMAND.md 5.6). 공개 트랜잭션과 분리된 새 트랜잭션에서 실행한다.
    화면에서 추천을 눌렀는지는 받지 않고, 옵션별 준비 수량이 추천 배분과 모두 같으면 적용한 것으로 본다.
 */
@Component
public class StockRecommendationSnapshotRecorder {

    private final StockRecommendationService stockRecommendationService;
    private final StockRecommendationRepository repository;
    private final DropEmbeddingRepository embeddingRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public StockRecommendationSnapshotRecorder(
            StockRecommendationService stockRecommendationService,
            StockRecommendationRepository repository,
            DropEmbeddingRepository embeddingRepository,
            ObjectMapper objectMapper
    ) {
        this(stockRecommendationService, repository, embeddingRepository, objectMapper, Clock.systemUTC());
    }

    StockRecommendationSnapshotRecorder(
            StockRecommendationService stockRecommendationService,
            StockRecommendationRepository repository,
            DropEmbeddingRepository embeddingRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.stockRecommendationService = stockRecommendationService;
        this.repository = repository;
        this.embeddingRepository = embeddingRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long dropId) {
        repository.findPublishedDrop(dropId)
                .filter(drop -> drop.categoryId() != null && !drop.options().isEmpty())
                .ifPresent(this::record);
    }

    private void record(PublishedDrop drop) {
        List<PublishedOption> options = drop.options();
        long[] unitPrices = options.stream().mapToLong(PublishedOption::unitPrice).toArray();
        int[] quantities = options.stream().mapToInt(PublishedOption::quantity).toArray();
        // 표본 선택도 공개 시점 DROP의 카탈로그 텍스트로 같은 방식을 쓴다(5.6). 공개 응답이 임베딩 API를 기다리지 않도록
        // 판매자가 같은 내용으로 추천을 받아 캐시에 남은 요청 임베딩만 쓰고, 없으면 카테고리 기준이다
        String queryVector = embeddingRepository.findSource(drop.dropId())
                .map(stockRecommendationService::cachedQueryVector)
                .orElse(null);
        StockRecommendationResponse result = stockRecommendationService.calculate(drop.sellerId(), drop.categoryId(),
                unitPrices, quantities, StockRecommendationService.DEFAULT_SAFETY_STOCK_RATE, queryVector);

        List<OptionRecommendation> recommended = result.recommendation().options();
        List<Map<String, Object>> prepared = new ArrayList<>();
        boolean applied = result.recommendation().recommended() != null;
        for (int i = 0; i < options.size(); i++) {
            Integer recommendedQuantity = recommended.get(i).recommendedQuantity();
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("optionId", options.get(i).optionId());
            option.put("quantity", options.get(i).quantity());
            option.put("recommendedQuantity", recommendedQuantity);
            prepared.add(option);
            applied = applied && Objects.equals(recommendedQuantity, options.get(i).quantity());
        }
        repository.saveSnapshot(
                drop.dropId(),
                result.calculationVersion(),
                result.basis() == null ? null : result.basis().name(),
                objectMapper.writeValueAsString(result),
                result.recommendation().recommended(),
                options.stream().mapToInt(PublishedOption::quantity).sum(),
                objectMapper.writeValueAsString(prepared),
                applied,
                OffsetDateTime.now(clock));
    }
}
