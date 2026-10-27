package org.example.grab.domain.demand.service;

import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.domain.demand.config.StockRecommendationProperties;
import org.example.grab.domain.demand.dto.SalesSample;
import org.example.grab.domain.demand.dto.StockRecommendationRequest;
import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionInput;
import org.example.grab.domain.demand.dto.StockRecommendationResponse;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.MarketSales;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.OwnSales;
import org.example.grab.domain.demand.repository.StockRecommendationRepository;
import org.example.grab.domain.demand.repository.StockRecommendationRepository.PriceRange;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.example.grab.global.common.ErrorResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Clock clock;

    @Autowired
    public StockRecommendationService(
            StockRecommendationRepository repository,
            CategoryService categoryService,
            StockRecommendationProperties properties
    ) {
        this(repository, categoryService, properties, Clock.systemUTC());
    }

    StockRecommendationService(
            StockRecommendationRepository repository,
            CategoryService categoryService,
            StockRecommendationProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.categoryService = categoryService;
        this.properties = properties;
        this.clock = clock;
    }

    // 카테고리 검증은 DROP 저장과 같은 오류 형식을 쓴다(DROP.md 2.1)
    @Transactional(readOnly = true)
    public StockRecommendationResponse recommend(long sellerId, StockRecommendationRequest request) {
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
        return calculate(sellerId, request.categoryId(), unitPrices, quantities, safetyStockRate);
    }

    /*
        공개 시점 기록(5.6)도 같은 계산을 쓴다. 시장 표본은 오늘 0시(KST) 이전 종료분으로 고정해 하루 동안 값이 바뀌지 않게 한다.
     */
    StockRecommendationResponse calculate(long sellerId, long categoryId, long[] unitPrices, int[] quantities,
                                          BigDecimal safetyStockRate) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime marketAsOf = now.atZoneSameInstant(KST).toLocalDate().atStartOfDay(KST).toOffsetDateTime();
        long referencePrice = StockRecommendationCalculator.referencePrice(unitPrices, quantities);
        PriceRange priceRange = priceRange(referencePrice);
        int limit = properties.maxSampleDrops();

        OwnSales own = StockRecommendationCalculator.ownSales(
                repository.findOwnSales(sellerId, categoryId, priceRange, limit));
        MarketSales market = StockRecommendationCalculator.marketSales(
                repository.findMarketSales(sellerId, categoryId, marketAsOf, priceRange, limit), marketAsOf);
        StockRecommendationCalculator.Result result = StockRecommendationCalculator.recommend(
                own, market, quantities, safetyStockRate, properties.soldOutThreshold());
        List<SalesSample> priceSamples = referencePrice == 0
                ? List.of()
                : repository.findMarketSales(sellerId, categoryId, marketAsOf, null,
                        properties.priceReferenceSampleDrops());

        return new StockRecommendationResponse(
                now.atZoneSameInstant(KST).toOffsetDateTime(),
                StockRecommendationCalculator.CALCULATION_VERSION,
                referencePrice,
                own,
                market,
                result.basis(),
                result.recommendation(),
                StockRecommendationCalculator.priceReference(referencePrice, priceSamples, properties.priceBandRatio()),
                result.dataGaps());
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
