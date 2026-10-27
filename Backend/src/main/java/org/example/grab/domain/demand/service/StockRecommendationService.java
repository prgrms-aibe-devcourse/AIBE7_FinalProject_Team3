package org.example.grab.domain.demand.service;

import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.domain.demand.config.StockRecommendationProperties;
import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.ConversionConfidence;
import org.example.grab.domain.demand.dto.SampleSelection;
import org.example.grab.domain.demand.dto.SalesSample;
import org.example.grab.domain.demand.dto.StockRecommendationRequest;
import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionInput;
import org.example.grab.domain.demand.dto.StockRecommendationResponse;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.MarketSales;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.OwnSales;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.SampleSelections;
import org.example.grab.domain.demand.embedding.DropEmbeddingQueryService;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.OptionGroup;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.Source;
import org.example.grab.domain.demand.repository.StockRecommendationRepository;
import org.example.grab.domain.demand.repository.StockRecommendationRepository.PriceRange;
import org.example.grab.domain.demand.repository.StockRecommendationRepository.Similarity;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.common.ErrorResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/*
    DROP 생성·DRAFT 편집 단계의 재고 추천과 가격대 참고(DEMAND.md 5절). 계산만 하고 저장하지 않는다.
    WISH 단계 수요 분석(1~4절)과 별개이며, 같은 보호 규칙·신뢰도 기준·옵션 배분을 쓴다.
 */
@Service
public class StockRecommendationService {

    static final BigDecimal DEFAULT_SAFETY_STOCK_RATE = new BigDecimal("0.10");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StockRecommendationRepository repository;
    private final CategoryService categoryService;
    private final StockRecommendationProperties properties;
    private final DropEmbeddingQueryService queryEmbeddings;
    // 비슷한 상품으로 볼 코사인 유사도 기준(DEMAND.md 1.2·5.3)
    private final double similarityMinScore;
    private final Clock clock;

    @Autowired
    public StockRecommendationService(
            StockRecommendationRepository repository,
            CategoryService categoryService,
            StockRecommendationProperties properties,
            DropEmbeddingQueryService queryEmbeddings,
            @Value("${grab.demand.similarity.min-score:0.75}") double similarityMinScore
    ) {
        this(repository, categoryService, properties, queryEmbeddings, similarityMinScore, Clock.systemUTC());
    }

    StockRecommendationService(
            StockRecommendationRepository repository,
            CategoryService categoryService,
            StockRecommendationProperties properties,
            DropEmbeddingQueryService queryEmbeddings,
            double similarityMinScore,
            Clock clock
    ) {
        this.repository = repository;
        this.categoryService = categoryService;
        this.properties = properties;
        this.queryEmbeddings = queryEmbeddings;
        this.similarityMinScore = similarityMinScore;
        this.clock = clock;
    }

    /*
        추천 결과와 기준별로 계산에 쓴 표본. 옵션별 배분(DEMAND.md 7절)이 같은 표본 DROP의 옵션을 읽는 데 쓴다.
     */
    public record Calculation(StockRecommendationResponse response, List<SalesSample> ownSamples,
                              List<SalesSample> marketSamples) {

        // 기준(basis)의 표본. 기준이 없으면 빈 목록이다
        public List<SalesSample> basisSamples() {
            if (response.basis() == null) {
                return List.of();
            }
            return response.basis() == ConversionBasis.OWN ? ownSamples : marketSamples;
        }
    }

    // 카테고리 검증은 DROP 저장과 같은 오류 형식을 쓴다(DROP.md 2.1)
    @Transactional(readOnly = true)
    public StockRecommendationResponse recommend(long sellerId, StockRecommendationRequest request) {
        return calculateWithSamples(sellerId, request).response();
    }

    @Transactional(readOnly = true)
    public Calculation calculateWithSamples(long sellerId, StockRecommendationRequest request) {
        if (!categoryService.isActive(request.categoryId())) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_FAILED,
                    List.of(new ErrorResponse.FieldError("categoryId", "존재하지 않거나 비활성인 카테고리입니다.")));
        }
        List<OptionInput> options = request.options();
        long[] unitPrices = options.stream().mapToLong(OptionInput::unitPrice).toArray();
        int[] quantities = options.stream().mapToInt(option -> option.quantity() == null ? 0 : option.quantity()).toArray();
        BigDecimal safetyStockRate = request.safetyStockRate() == null
                ? DEFAULT_SAFETY_STOCK_RATE : request.safetyStockRate();
        String queryVector = queryVector(new Source(
                categoryService.findCategory(request.categoryId()).map(category -> category.name()).orElse(""),
                request.name(),
                request.description(),
                request.optionGroups() == null ? List.of() : request.optionGroups().stream()
                        .map(group -> new OptionGroup(group.name(), group.values()))
                        .toList()));
        return compute(sellerId, request.categoryId(), unitPrices, quantities, safetyStockRate, queryVector);
    }

    // 요청 임베딩(pgvector 입력 형식). 만들 수 없으면 null이고 카테고리 기준으로 계산한다(5.1)
    private String queryVector(Source source) {
        return queryEmbeddings.embed(source).orElse(null);
    }

    // 공개 시점 기록(5.6)은 캐시에 있는 요청 임베딩만 쓴다. 없으면 null(카테고리 기준)
    String cachedQueryVector(Source source) {
        return queryEmbeddings.cachedOnly(source).orElse(null);
    }

    /*
        공개 시점 기록(5.6)도 같은 계산을 쓴다. 시장 표본은 오늘 0시(KST) 이전 종료분으로 고정해 하루 동안 값이 바뀌지 않게 한다.
     */
    StockRecommendationResponse calculate(long sellerId, long categoryId, long[] unitPrices, int[] quantities,
                                          BigDecimal safetyStockRate, String queryVector) {
        return compute(sellerId, categoryId, unitPrices, quantities, safetyStockRate, queryVector).response();
    }

    private Calculation compute(long sellerId, long categoryId, long[] unitPrices, int[] quantities,
                                BigDecimal safetyStockRate, String queryVector) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime marketAsOf = now.atZoneSameInstant(KST).toLocalDate().atStartOfDay(KST).toOffsetDateTime();
        long referencePrice = StockRecommendationCalculator.referencePrice(unitPrices, quantities);
        PriceRange priceRange = priceRange(referencePrice);
        int limit = properties.maxSampleDrops();

        Similarity similarity = queryVector == null ? null : new Similarity(queryVector, 1 - similarityMinScore);

        // 기준마다 비슷한 상품 표본을 먼저 보고, 신뢰도가 NONE이면 카테고리 표본으로 계산한다(5.1)
        SampleSelection ownSelection = SampleSelection.CATEGORY;
        List<SalesSample> ownSamples = null;
        OwnSales own = null;
        if (similarity != null) {
            ownSamples = repository.findSimilarOwnSales(sellerId, categoryId, priceRange, similarity, limit);
            own = StockRecommendationCalculator.ownSales(ownSamples);
            ownSelection = SampleSelection.SIMILAR;
        }
        if (own == null || own.confidence() == ConversionConfidence.NONE) {
            ownSamples = repository.findOwnSales(sellerId, categoryId, priceRange, limit);
            own = StockRecommendationCalculator.ownSales(ownSamples);
            ownSelection = SampleSelection.CATEGORY;
        }
        SampleSelection marketSelection = SampleSelection.CATEGORY;
        List<SalesSample> marketSamples = null;
        MarketSales market = null;
        if (similarity != null) {
            marketSamples = repository.findSimilarMarketSales(
                    sellerId, categoryId, marketAsOf, priceRange, similarity, limit);
            market = StockRecommendationCalculator.marketSales(marketSamples, marketAsOf);
            marketSelection = SampleSelection.SIMILAR;
        }
        if (market == null || market.confidence() == ConversionConfidence.NONE) {
            marketSamples = repository.findMarketSales(sellerId, categoryId, marketAsOf, priceRange, limit);
            market = StockRecommendationCalculator.marketSales(marketSamples, marketAsOf);
            marketSelection = SampleSelection.CATEGORY;
        }
        StockRecommendationCalculator.Result result = StockRecommendationCalculator.recommend(
                own, market, quantities, safetyStockRate, properties.soldOutThreshold());
        List<SalesSample> priceSamples = referencePrice == 0
                ? List.of()
                : repository.findMarketSales(sellerId, categoryId, marketAsOf, null,
                        properties.priceReferenceSampleDrops());

        StockRecommendationResponse response = new StockRecommendationResponse(
                now.atZoneSameInstant(KST).toOffsetDateTime(),
                StockRecommendationCalculator.CALCULATION_VERSION,
                referencePrice,
                own,
                market,
                result.basis(),
                new SampleSelections(ownSelection, marketSelection),
                result.recommendation(),
                StockRecommendationCalculator.priceReference(referencePrice, priceSamples, properties.priceBandRatio()),
                result.dataGaps());
        return new Calculation(response, nullToEmpty(ownSamples), nullToEmpty(marketSamples));
    }

    private static List<SalesSample> nullToEmpty(List<SalesSample> samples) {
        return samples == null ? List.of() : samples;
    }

    // 대표 가격이 0이면 가격 조건 없이 고른다(5.1)
    private PriceRange priceRange(long referencePrice) {
        if (referencePrice == 0) {
            return null;
        }
        BigDecimal reference = BigDecimal.valueOf(referencePrice);
        return new PriceRange(
                reference.multiply(BigDecimal.ONE.subtract(properties.priceBandRatio())),
                reference.multiply(BigDecimal.ONE.add(properties.priceBandRatio())));
    }
}
