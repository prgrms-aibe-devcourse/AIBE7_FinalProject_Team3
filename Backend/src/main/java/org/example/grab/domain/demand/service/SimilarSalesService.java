package org.example.grab.domain.demand.service;

import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.domain.demand.config.StockRecommendationProperties;
import org.example.grab.domain.demand.dto.SimilarSalesRequest;
import org.example.grab.domain.demand.dto.SimilarSalesResponse;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Basis;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.OthersSimilarSales;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.OwnSimilarSales;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Reason;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Scope;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Selection;
import org.example.grab.domain.demand.embedding.DropEmbeddingQueryService;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.OptionGroup;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.Source;
import org.example.grab.domain.demand.repository.DemandAnalysisRepository.TargetDrop;
import org.example.grab.domain.demand.repository.SimilarSalesRepository;
import org.example.grab.domain.demand.repository.SimilarSalesRepository.SampleQuery;
import org.example.grab.domain.demand.repository.SimilarSalesRepository.SellerScope;
import org.example.grab.domain.demand.repository.SimilarSalesRepository.SimilarSample;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/*
    비슷한 DROP 실적(DEMAND.md 7절, DEMAND-013). 내 지난 비슷한 DROP과 다른 판매자의 비슷한 굿즈 결과를 나란히 계산하고,
    내 비슷한 DROP이 충분하면 내 것을, 부족하면 다른 판매자 것을 기준으로 삼는다(7.2).
    임베딩은 표본 검색에만 쓰고, 지표는 SQL 집계와 결정적 계산으로만 만든다. 저장하지 않는다.
    생성 단계의 요청 임베딩(외부 호출, 최대 수 초)은 DB 커넥션을 쥐지 않도록 트랜잭션 밖에서 만든다.
 */
@Service
public class SimilarSalesService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int MAX_SAMPLE_DROPS = 20;
    // 대상이 없는 생성 단계에서 제외할 DROP ID 자리
    private static final long NO_DROP = -1;

    private final SimilarSalesRepository repository;
    private final DemandAnalysisService demandAnalysisService;
    private final CategoryService categoryService;
    private final DropEmbeddingQueryService queryEmbeddings;
    private final StockRecommendationProperties stockProperties;
    private final double similarityMinScore;
    private final int minOwnDrops;
    private final int maxPeerDrops;
    private final TransactionTemplate readOnlyTransaction;
    private final Clock clock;

    @Autowired
    public SimilarSalesService(
            SimilarSalesRepository repository,
            DemandAnalysisService demandAnalysisService,
            CategoryService categoryService,
            DropEmbeddingQueryService queryEmbeddings,
            StockRecommendationProperties stockProperties,
            PlatformTransactionManager transactionManager,
            @Value("${grab.demand.similarity.min-score:0.75}") double similarityMinScore,
            @Value("${grab.demand.similar-sales.min-own-drops:3}") int minOwnDrops,
            @Value("${grab.demand.similar-sales.max-peer-drops:3}") int maxPeerDrops
    ) {
        this(repository, demandAnalysisService, categoryService, queryEmbeddings, stockProperties, transactionManager,
                similarityMinScore, minOwnDrops, maxPeerDrops, Clock.systemUTC());
    }

    SimilarSalesService(
            SimilarSalesRepository repository,
            DemandAnalysisService demandAnalysisService,
            CategoryService categoryService,
            DropEmbeddingQueryService queryEmbeddings,
            StockRecommendationProperties stockProperties,
            PlatformTransactionManager transactionManager,
            double similarityMinScore,
            int minOwnDrops,
            int maxPeerDrops,
            Clock clock
    ) {
        this.repository = repository;
        this.demandAnalysisService = demandAnalysisService;
        this.categoryService = categoryService;
        this.queryEmbeddings = queryEmbeddings;
        this.stockProperties = stockProperties;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.similarityMinScore = similarityMinScore;
        this.minOwnDrops = minOwnDrops;
        this.maxPeerDrops = maxPeerDrops;
        this.clock = clock;
    }

    // WISH 단계(7.4). 권한·상태 검사는 수요 분석 조회와 같다(2.1)
    @Transactional(readOnly = true)
    public SimilarSalesResponse forWishDrop(long sellerId, long dropId) {
        TargetDrop drop = demandAnalysisService.findOwnedDrop(sellerId, dropId);
        DemandAnalysisService.requireAnalyzable(drop, OffsetDateTime.now(clock));
        return calculate(new Target(sellerId, drop.categoryId(), dropId, repository.findEmbedding(dropId).orElse(null),
                repository.findReferencePrice(dropId)));
    }

    /*
        생성 단계(7.5). 카테고리 검증은 재고 추천과 같다(5.5).
        카테고리 확인·요청 임베딩은 트랜잭션 없이 하고, 표본 조회·계산만 읽기 전용 트랜잭션에서 한다.
     */
    public SimilarSalesResponse forForm(long sellerId, SimilarSalesRequest request) {
        if (!categoryService.isActive(request.categoryId())) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_FAILED,
                    List.of(new ErrorResponse.FieldError("categoryId", "존재하지 않거나 비활성인 카테고리입니다.")));
        }
        String queryVector = queryEmbeddings.embed(new Source(
                        categoryService.findCategory(request.categoryId()).map(category -> category.name()).orElse(""),
                        request.name(),
                        request.description(),
                        request.optionGroups() == null ? List.of() : request.optionGroups().stream()
                                .map(group -> new OptionGroup(group.name(), group.values()))
                                .toList()))
                .orElse(null);
        long referencePrice = Math.round(request.unitPrices().stream().mapToLong(Long::longValue).average().orElse(0));
        Target target = new Target(sellerId, request.categoryId(), NO_DROP, queryVector, referencePrice);
        return readOnlyTransaction.execute(status -> calculate(target));
    }

    private record Target(long sellerId, long categoryId, long excludedDropId, String queryVector,
                          long referencePrice) {
    }

    private SimilarSalesResponse calculate(Target target) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime asOf = now.atZoneSameInstant(KST).toLocalDate().atStartOfDay(KST).toOffsetDateTime();

        // 내 DROP: 비슷한 DROP → 없으면 내 전체 종료 DROP(같은 카테고리)
        List<SimilarSample> ownSimilar = target.queryVector() == null ? List.of()
                : repository.findSamples(query(target, SellerScope.OWN, true, asOf, false));
        long ownEnded = repository.countOwnEndedDrops(target.sellerId());
        Reason reason = ownSimilar.size() >= minOwnDrops ? Reason.OWN_ENOUGH
                : !ownSimilar.isEmpty() ? Reason.OWN_FEW
                : ownEnded > 0 ? Reason.OWN_NOT_SIMILAR
                : Reason.FIRST_SALE;
        OwnSimilarSales own = null;
        if (!ownSimilar.isEmpty()) {
            own = SimilarSalesCalculator.own(ownSimilar, Selection.SIMILAR);
        } else if (ownEnded > 0) {
            List<SimilarSample> ownAll = repository.findSamples(query(target, SellerScope.OWN, false, asOf,
                    false));
            own = ownAll.isEmpty() ? null : SimilarSalesCalculator.own(ownAll, Selection.ALL);
        }

        OthersSimilarSales others = others(target, reason, asOf);
        Basis basis = reason == Reason.OWN_ENOUGH ? Basis.OWN : others.available() ? Basis.OTHERS : null;
        return new SimilarSalesResponse(basis, reason, own, others);
    }

    /*
        다른 판매자: 비슷한 굿즈 → 같은 카테고리 전체 순으로 넓힌다. 첫 판매면 비슷한 단계 판매자부터 보고,
        보호 단위를 못 넘으면 전체 판매자로 넓힌다(7.1). 끝까지 못 넘으면 마지막 결과(NONE)를 돌려준다.
     */
    private OthersSimilarSales others(Target target, Reason reason, OffsetDateTime asOf) {
        List<Scope> scopes = reason == Reason.FIRST_SALE
                ? List.of(Scope.NEW_SELLERS, Scope.ALL_SELLERS)
                : List.of(Scope.ALL_SELLERS);
        OthersSimilarSales last = null;
        for (Scope scope : scopes) {
            SellerScope sellerScope = scope == Scope.NEW_SELLERS ? SellerScope.NEW_SELLERS : SellerScope.OTHERS;
            if (target.queryVector() != null) {
                last = SimilarSalesCalculator.others(
                        repository.findSamples(query(target, sellerScope, true, asOf, true)),
                        Selection.SIMILAR, scope, asOf);
                if (last.available()) {
                    return last;
                }
            }
            last = SimilarSalesCalculator.others(
                    repository.findSamples(query(target, sellerScope, false, asOf, true)),
                    Selection.CATEGORY, scope, asOf);
            if (last.available()) {
                return last;
            }
        }
        return last;
    }

    // 가격대 조건은 다른 판매자 표본에만 건다(7.1). 대표 가격이 0이면 가격 조건 없이 고른다
    private SampleQuery query(Target target, SellerScope scope, boolean similar, OffsetDateTime asOf,
                              boolean priceBand) {
        BigDecimal minPrice = null;
        BigDecimal maxPrice = null;
        if (priceBand && target.referencePrice() > 0) {
            BigDecimal reference = BigDecimal.valueOf(target.referencePrice());
            minPrice = reference.multiply(BigDecimal.ONE.subtract(stockProperties.priceBandRatio()));
            maxPrice = reference.multiply(BigDecimal.ONE.add(stockProperties.priceBandRatio()));
        }
        return new SampleQuery(scope, target.sellerId(), target.categoryId(), target.excludedDropId(), asOf,
                minPrice, maxPrice, similar ? target.queryVector() : null, 1 - similarityMinScore, maxPeerDrops,
                MAX_SAMPLE_DROPS);
    }
}
