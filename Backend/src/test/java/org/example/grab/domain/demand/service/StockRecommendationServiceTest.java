package org.example.grab.domain.demand.service;

import org.example.grab.domain.category.dto.response.CategoryResponse;
import org.example.grab.domain.category.service.CategoryService;
import org.example.grab.domain.demand.config.StockRecommendationProperties;
import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.SalesSample;
import org.example.grab.domain.demand.dto.SampleSelection;
import org.example.grab.domain.demand.dto.StockRecommendationRequest;
import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionGroupInput;
import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionInput;
import org.example.grab.domain.demand.dto.StockRecommendationResponse;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.PricePosition;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.SampleSelections;
import org.example.grab.domain.demand.embedding.DropEmbeddingQueryService;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.OptionGroup;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.Source;
import org.example.grab.domain.demand.repository.StockRecommendationRepository;
import org.example.grab.domain.demand.repository.StockRecommendationRepository.PriceRange;
import org.example.grab.domain.demand.repository.StockRecommendationRepository.Similarity;
import org.example.grab.global.common.ErrorResponse;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class StockRecommendationServiceTest {

    private static final long SELLER_ID = 3L;
    private static final long CATEGORY_ID = 7L;
    // 2026-10-26 18:00 KST
    private static final Instant NOW = Instant.parse("2026-10-26T09:00:00Z");
    private static final OffsetDateTime TODAY_KST = OffsetDateTime.parse("2026-10-26T00:00:00+09:00");

    private StockRecommendationRepository repository;
    private CategoryService categoryService;
    private DropEmbeddingQueryService queryEmbeddings;
    private StockRecommendationService service;

    @BeforeEach
    void setUp() {
        repository = mock(StockRecommendationRepository.class);
        categoryService = mock(CategoryService.class);
        queryEmbeddings = mock(DropEmbeddingQueryService.class);
        service = new StockRecommendationService(repository, categoryService,
                new StockRecommendationProperties(new BigDecimal("0.3"), new BigDecimal("0.5"), 20, 60),
                queryEmbeddings, 0.75, Clock.fixed(NOW, ZoneOffset.UTC));
        given(queryEmbeddings.embed(any())).willReturn(Optional.empty());
        given(categoryService.isActive(CATEGORY_ID)).willReturn(true);
        given(repository.findOwnSales(anyLong(), anyLong(), any(), anyInt())).willReturn(List.of());
        given(repository.findMarketSales(anyLong(), anyLong(), any(), any(), anyInt())).willReturn(List.of());
    }

    @Test
    @DisplayName("대표 가격 ±30% 범위로 내 실적을, 오늘 0시(KST) 이전 종료분으로 시장 표본을 고르고 기본 안전 재고율 10%를 쓴다")
    void recommend_queriesSamplesWithPriceBandAndAsOf() {
        // given: 내 실적 4개(판매량 10·20·30·40)
        given(repository.findOwnSales(eq(SELLER_ID), eq(CATEGORY_ID), any(), eq(20))).willReturn(List.of(
                sample(10), sample(20), sample(30), sample(40)));

        // when
        StockRecommendationResponse response = service.recommend(SELLER_ID, request(20000L, 20000L));

        // then
        PriceRange expectedRange = new PriceRange(new BigDecimal("14000.0"), new BigDecimal("26000.0"));
        verify(repository).findOwnSales(SELLER_ID, CATEGORY_ID, expectedRange, 20);
        verify(repository).findMarketSales(SELLER_ID, CATEGORY_ID, TODAY_KST, expectedRange, 20);
        verify(repository).findMarketSales(SELLER_ID, CATEGORY_ID, TODAY_KST, null, 60);
        assertThat(response.calculatedAt()).isEqualTo(OffsetDateTime.parse("2026-10-26T18:00:00+09:00"));
        assertThat(response.calculationVersion()).isEqualTo("s1");
        assertThat(response.referencePrice()).isEqualTo(20000);
        assertThat(response.basis()).isEqualTo(ConversionBasis.OWN);
        assertThat(response.recommendation().recommended()).isEqualTo(22);
        assertThat(response.recommendation().safetyStockRate()).isEqualByComparingTo("0.10");
        assertThat(response.market().asOf()).isEqualTo(TODAY_KST);
    }

    @Test
    @DisplayName("대표 가격이 0이면 가격 조건 없이 고르고 가격대 참고는 조회하지 않는다")
    void recommend_skipsPriceBandForZeroPrice() {
        StockRecommendationResponse response = service.recommend(SELLER_ID, request(0L));

        verify(repository).findOwnSales(eq(SELLER_ID), eq(CATEGORY_ID), isNull(), eq(20));
        verify(repository, never()).findMarketSales(anyLong(), anyLong(), any(), isNull(), eq(60));
        assertThat(response.priceReference().position()).isEqualTo(PricePosition.UNKNOWN);
        assertThat(response.priceReference().bands()).isEmpty();
    }

    @Test
    @DisplayName("없거나 비활성인 카테고리는 DROP 저장과 같은 VALIDATION_FAILED(categoryId)로 거부하고 표본을 조회하지 않는다")
    void recommend_rejectsInactiveCategory() {
        given(categoryService.isActive(CATEGORY_ID)).willReturn(false);

        assertThatThrownBy(() -> service.recommend(SELLER_ID, request(20000L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).extracting(ErrorResponse.FieldError::field)
                            .containsExactly("categoryId");
                });
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("상품명·설명으로 만든 요청 임베딩이 있으면 기준마다 비슷한 상품(유사도 0.75 이상) 표본을 먼저 쓴다")
    void recommend_prefersSimilarSamples() {
        // given: 요청 임베딩과 비슷한 내 상품 4개(판매량 10·20·30·40). 카테고리 표본은 비어 있다
        given(categoryService.findCategory(CATEGORY_ID))
                .willReturn(Optional.of(new CategoryResponse(CATEGORY_ID, "FASHION", "패션")));
        given(queryEmbeddings.embed(new Source("패션", "티셔츠", "코튼 티셔츠",
                List.of(new OptionGroup("색상", List.of("블랙")))))).willReturn(Optional.of(VECTOR));
        Similarity similarity = new Similarity(VECTOR, 0.25);
        given(repository.findSimilarOwnSales(eq(SELLER_ID), eq(CATEGORY_ID), any(), eq(similarity), eq(20)))
                .willReturn(List.of(sample(10), sample(20), sample(30), sample(40)));

        // when
        StockRecommendationResponse response = service.recommend(SELLER_ID, catalogRequest());

        // then: 내 실적은 비슷한 상품으로, 시장 평균은 비슷한 상품 표본이 보호 단위 미달이라 카테고리로 계산한다
        assertThat(response.sampleSelection())
                .isEqualTo(new SampleSelections(SampleSelection.SIMILAR, SampleSelection.CATEGORY));
        assertThat(response.basis()).isEqualTo(ConversionBasis.OWN);
        assertThat(response.own().dropCount()).isEqualTo(4);
        verify(repository).findSimilarMarketSales(eq(SELLER_ID), eq(CATEGORY_ID), eq(TODAY_KST), any(), eq(similarity),
                eq(20));
        verify(repository).findMarketSales(eq(SELLER_ID), eq(CATEGORY_ID), eq(TODAY_KST), any(), eq(20));
        verify(repository, never()).findOwnSales(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("비슷한 상품 표본의 신뢰도가 NONE이면 카테고리 표본으로 계산한다")
    void recommend_fallsBackToCategoryWhenSimilarIsThin() {
        given(queryEmbeddings.embed(any())).willReturn(Optional.of(VECTOR));
        given(repository.findSimilarOwnSales(anyLong(), anyLong(), any(), any(), anyInt()))
                .willReturn(List.of(sample(10)));
        given(repository.findOwnSales(eq(SELLER_ID), eq(CATEGORY_ID), any(), eq(20)))
                .willReturn(List.of(sample(10), sample(20), sample(30)));

        StockRecommendationResponse response = service.recommend(SELLER_ID, catalogRequest());

        assertThat(response.sampleSelection())
                .isEqualTo(new SampleSelections(SampleSelection.CATEGORY, SampleSelection.CATEGORY));
        assertThat(response.own().dropCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("요청 임베딩을 만들지 못하면(꺼짐·상품명 없음·실패) 비슷한 상품 표본을 조회하지 않는다")
    void recommend_usesCategoryWithoutQueryVector() {
        StockRecommendationResponse response = service.recommend(SELLER_ID, request(20000L));

        assertThat(response.sampleSelection())
                .isEqualTo(new SampleSelections(SampleSelection.CATEGORY, SampleSelection.CATEGORY));
        verify(repository, never()).findSimilarOwnSales(anyLong(), anyLong(), any(), any(), anyInt());
        verify(repository, never()).findSimilarMarketSales(anyLong(), anyLong(), any(), any(), any(), anyInt());
    }

    private static final String VECTOR = "[0.1,0.2]";

    private static StockRecommendationRequest catalogRequest() {
        return new StockRecommendationRequest(CATEGORY_ID, List.of(new OptionInput(20000L, null)), null,
                "티셔츠", "코튼 티셔츠", List.of(new OptionGroupInput("색상", List.of("블랙"))));
    }

    private static StockRecommendationRequest request(Long... unitPrices) {
        return new StockRecommendationRequest(CATEGORY_ID,
                java.util.Arrays.stream(unitPrices).map(price -> new OptionInput(price, null)).toList(), null,
                null, null, null);
    }

    private static SalesSample sample(int sold) {
        return new SalesSample(SELLER_ID, 20000, 50, sold, false);
    }
}
