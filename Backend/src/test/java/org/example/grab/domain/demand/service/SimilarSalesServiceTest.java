package org.example.grab.domain.demand.service;

import org.example.grab.domain.category.dto.response.CategoryResponse;
import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.domain.demand.config.StockRecommendationProperties;
import org.example.grab.domain.demand.dto.SimilarSalesRequest;
import org.example.grab.domain.demand.dto.SimilarSalesResponse;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Basis;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Reason;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Scope;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Selection;
import org.example.grab.domain.demand.embedding.DropEmbeddingQueryService;
import org.example.grab.domain.demand.error.DemandErrorCode;
import org.example.grab.domain.demand.repository.DemandAnalysisRepository.TargetDrop;
import org.example.grab.domain.demand.repository.SimilarSalesRepository;
import org.example.grab.domain.demand.repository.SimilarSalesRepository.SampleQuery;
import org.example.grab.domain.demand.repository.SimilarSalesRepository.SellerScope;
import org.example.grab.domain.demand.repository.SimilarSalesRepository.SimilarSample;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SimilarSalesServiceTest {

    private static final long SELLER_ID = 3L;
    private static final long CATEGORY_ID = 8L;
    private static final long DROP_ID = 100L;
    private static final String VECTOR = "[0.1,0.2]";
    // 2026-10-27 14:00 KST
    private static final Instant NOW = Instant.parse("2026-10-27T05:00:00Z");

    private SimilarSalesRepository repository;
    private DemandAnalysisService demandAnalysisService;
    private CategoryService categoryService;
    private DropEmbeddingQueryService queryEmbeddings;
    private PlatformTransactionManager transactionManager;
    private SimilarSalesService service;

    @BeforeEach
    void setUp() {
        repository = mock(SimilarSalesRepository.class);
        demandAnalysisService = mock(DemandAnalysisService.class);
        categoryService = mock(CategoryService.class);
        queryEmbeddings = mock(DropEmbeddingQueryService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        given(repository.findSamples(any())).willReturn(List.of());
        given(repository.findEmbedding(DROP_ID)).willReturn(Optional.of(VECTOR));
        given(repository.findReferencePrice(DROP_ID)).willReturn(20000L);
        given(demandAnalysisService.findOwnedDrop(SELLER_ID, DROP_ID)).willReturn(new TargetDrop(DROP_ID, SELLER_ID,
                "WISH", CATEGORY_ID, OffsetDateTime.parse("2026-11-01T00:00:00Z")));
        given(categoryService.isActive(CATEGORY_ID)).willReturn(true);
        given(categoryService.findCategory(CATEGORY_ID))
                .willReturn(Optional.of(new CategoryResponse(CATEGORY_ID, "HOBBY", "취미·굿즈")));
        service = new SimilarSalesService(repository, demandAnalysisService, categoryService, queryEmbeddings,
                new StockRecommendationProperties(new BigDecimal("0.3"), new BigDecimal("0.5"), 20, 60),
                transactionManager, 0.75, 3, 3, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("내 비슷한 DROP이 3개 이상이면 내 것을 기준으로 하고, 다른 판매자 비슷한 굿즈도 함께 반환한다")
    void forWishDrop_ownEnough() {
        // given
        given(repository.findSamples(argThat(query -> isOwnSimilar(query)))).willReturn(samples(4, 1));
        given(repository.findSamples(argThat(query -> isOthers(query, true)))).willReturn(samples(12, 6));
        given(repository.countOwnEndedDrops(SELLER_ID)).willReturn(4L);

        // when
        SimilarSalesResponse response = service.forWishDrop(SELLER_ID, DROP_ID);

        // then
        assertThat(response.basis()).isEqualTo(Basis.OWN);
        assertThat(response.reason()).isEqualTo(Reason.OWN_ENOUGH);
        assertThat(response.own().selection()).isEqualTo(Selection.SIMILAR);
        assertThat(response.own().dropCount()).isEqualTo(4);
        assertThat(response.others().selection()).isEqualTo(Selection.SIMILAR);
        assertThat(response.others().scope()).isEqualTo(Scope.ALL_SELLERS);
        // 다른 판매자에만 대표 가격 ±30%를 건다
        ArgumentCaptor<SampleQuery> queries = ArgumentCaptor.forClass(SampleQuery.class);
        verify(repository, atLeastOnce()).findSamples(queries.capture());
        assertThat(queries.getAllValues()).filteredOn(query -> query.scope() == SellerScope.OWN)
                .allMatch(query -> query.minPrice() == null && query.maxDistance() == 0.25);
        assertThat(queries.getAllValues()).filteredOn(query -> query.scope() == SellerScope.OTHERS)
                .allMatch(query -> query.minPrice().compareTo(new BigDecimal("14000")) == 0);
    }

    @Test
    @DisplayName("내 비슷한 DROP이 1~2개면 다른 판매자 것을 기준으로 하고 내 것은 참고로 함께 반환한다")
    void forWishDrop_ownFew() {
        given(repository.findSamples(argThat(query -> isOwnSimilar(query)))).willReturn(samples(2, 1));
        given(repository.findSamples(argThat(query -> isOthers(query, true)))).willReturn(samples(12, 6));
        given(repository.countOwnEndedDrops(SELLER_ID)).willReturn(5L);

        SimilarSalesResponse response = service.forWishDrop(SELLER_ID, DROP_ID);

        assertThat(response.basis()).isEqualTo(Basis.OTHERS);
        assertThat(response.reason()).isEqualTo(Reason.OWN_FEW);
        assertThat(response.own().dropCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("내 종료 DROP은 있지만 비슷한 것이 없으면 내 전체 실적을 참고로, 다른 판매자 비슷한 굿즈가 부족하면 카테고리 전체로 넓힌다")
    void forWishDrop_ownNotSimilarAndCategoryFallback() {
        given(repository.findSamples(argThat(query -> query != null && query.scope() == SellerScope.OWN
                && query.queryVector() == null)))
                .willReturn(samples(5, 1));
        given(repository.findSamples(argThat(query -> isOthers(query, false)))).willReturn(samples(15, 5));
        given(repository.countOwnEndedDrops(SELLER_ID)).willReturn(5L);

        SimilarSalesResponse response = service.forWishDrop(SELLER_ID, DROP_ID);

        assertThat(response.reason()).isEqualTo(Reason.OWN_NOT_SIMILAR);
        assertThat(response.own().selection()).isEqualTo(Selection.ALL);
        assertThat(response.others().selection()).isEqualTo(Selection.CATEGORY);
        assertThat(response.basis()).isEqualTo(Basis.OTHERS);
    }

    @Test
    @DisplayName("첫 판매면 비슷한 단계 판매자부터 보고, 보호 단위를 못 넘으면 전체 판매자로 넓힌다")
    void forWishDrop_firstSale() {
        given(repository.countOwnEndedDrops(SELLER_ID)).willReturn(0L);
        given(repository.findSamples(argThat(query -> query != null && query.scope() == SellerScope.NEW_SELLERS)))
                .willReturn(samples(3, 2));
        given(repository.findSamples(argThat(query -> isOthers(query, true)))).willReturn(samples(10, 5));

        SimilarSalesResponse response = service.forWishDrop(SELLER_ID, DROP_ID);

        assertThat(response.reason()).isEqualTo(Reason.FIRST_SALE);
        assertThat(response.own()).isNull();
        assertThat(response.others().scope()).isEqualTo(Scope.ALL_SELLERS);
        assertThat(response.basis()).isEqualTo(Basis.OTHERS);
    }

    @Test
    @DisplayName("다른 판매자 자료가 끝까지 보호 단위에 못 미치면 기준 없이(null) 응답한다")
    void forWishDrop_noBasis() {
        given(repository.countOwnEndedDrops(SELLER_ID)).willReturn(0L);

        SimilarSalesResponse response = service.forWishDrop(SELLER_ID, DROP_ID);

        assertThat(response.basis()).isNull();
        assertThat(response.others().available()).isFalse();
    }

    @Test
    @DisplayName("WISH가 아니거나 판매가 시작된 DROP은 DEMAND_ANALYSIS_UNAVAILABLE이다")
    void forWishDrop_rejectsNonWish() {
        given(demandAnalysisService.findOwnedDrop(SELLER_ID, DROP_ID)).willReturn(new TargetDrop(DROP_ID, SELLER_ID,
                "GRAB", CATEGORY_ID, OffsetDateTime.parse("2026-10-20T00:00:00Z")));

        assertThatThrownBy(() -> service.forWishDrop(SELLER_ID, DROP_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DemandErrorCode.DEMAND_ANALYSIS_UNAVAILABLE);
    }

    @Test
    @DisplayName("생성 단계는 폼 입력으로 요청 임베딩을 만들고, 만들지 못하면 비슷한 DROP 조회 없이 넓힌다")
    void forForm_usesQueryEmbedding() {
        given(queryEmbeddings.embed(any())).willReturn(Optional.empty());
        given(repository.countOwnEndedDrops(SELLER_ID)).willReturn(2L);

        SimilarSalesResponse response = service.forForm(SELLER_ID, new SimilarSalesRequest(CATEGORY_ID, "키링", null,
                null, List.of(10000L, 14000L)));

        assertThat(response.reason()).isEqualTo(Reason.OWN_NOT_SIMILAR);
        ArgumentCaptor<SampleQuery> queries = ArgumentCaptor.forClass(SampleQuery.class);
        verify(repository, atLeastOnce()).findSamples(queries.capture());
        assertThat(queries.getAllValues()).allMatch(query -> query.queryVector() == null && query.excludedDropId() == -1);
        // 단순 평균 12000의 ±30%
        assertThat(queries.getAllValues()).filteredOn(query -> query.scope() == SellerScope.OTHERS)
                .allMatch(query -> query.maxPrice().compareTo(new BigDecimal("15600")) == 0);
    }

    @Test
    @DisplayName("생성 단계는 요청 임베딩을 만든 뒤에 읽기 전용 트랜잭션을 열어 외부 호출 동안 DB 커넥션을 쥐지 않는다")
    void forForm_embedsOutsideTransaction() {
        given(queryEmbeddings.embed(any())).willReturn(Optional.of(VECTOR));

        service.forForm(SELLER_ID, new SimilarSalesRequest(CATEGORY_ID, "키링", "아크릴", null, List.of(12000L)));

        InOrder order = inOrder(queryEmbeddings, transactionManager, repository);
        order.verify(queryEmbeddings).embed(any());
        order.verify(transactionManager).getTransaction(argThat(definition -> definition.isReadOnly()));
        order.verify(repository, atLeastOnce()).findSamples(any());
    }

    @Test
    @DisplayName("생성 단계에서 없거나 비활성인 카테고리는 VALIDATION_FAILED(categoryId)로 거부한다")
    void forForm_rejectsInactiveCategory() {
        given(categoryService.isActive(CATEGORY_ID)).willReturn(false);

        assertThatThrownBy(() -> service.forForm(SELLER_ID,
                new SimilarSalesRequest(CATEGORY_ID, null, null, null, List.of(1000L))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    private static boolean isOwnSimilar(SampleQuery query) {
        return query != null && query.scope() == SellerScope.OWN && query.queryVector() != null;
    }

    private static boolean isOthers(SampleQuery query, boolean similar) {
        return query != null && query.scope() == SellerScope.OTHERS && (query.queryVector() != null) == similar;
    }

    // count개 DROP을 sellers명이 나눠 가진 표본
    private static List<SimilarSample> samples(int count, int sellers) {
        return IntStream.range(0, count)
                .mapToObj(i -> new SimilarSample(i, 10 + i % sellers, 20, 15, i % 2 == 0, 8, null))
                .toList();
    }
}
