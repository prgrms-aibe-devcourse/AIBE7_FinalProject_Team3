package org.example.grab.domain.demand.service;

import org.example.grab.domain.demand.dto.SimilarSalesResponse.OthersSimilarSales;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.OwnSimilarSales;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Scope;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Selection;
import org.example.grab.domain.demand.repository.SimilarSalesRepository.SimilarSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SimilarSalesCalculatorTest {

    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-10-27T00:00:00+09:00");

    @Test
    @DisplayName("내 DROP은 매진 비율·매진 시간 중간값·판매율·WISH 대비 판매량을 그대로(소수 둘째 자리) 계산한다")
    void own_calculatesMetrics() {
        // given: 매진 3개(4·6·30분), 기간 만료 1개. WISH 기록은 둘만 있다
        List<SimilarSample> samples = List.of(
                new SimilarSample(1, 9, 30, 30, true, 4, 50),
                new SimilarSample(2, 9, 30, 30, true, 6, 40),
                new SimilarSample(3, 9, 30, 30, true, 30, null),
                new SimilarSample(4, 9, 30, 10, false, null, null));

        // when
        OwnSimilarSales own = SimilarSalesCalculator.own(samples, Selection.SIMILAR);

        // then
        assertThat(own.dropCount()).isEqualTo(4);
        assertThat(own.soldOutRate()).isEqualByComparingTo("0.75");
        assertThat(own.medianSellOutMinutes()).isEqualTo(6);
        assertThat(own.sellThroughRate()).isEqualByComparingTo("0.83");
        // (30 + 30) ÷ (50 + 40)
        assertThat(own.soldPerWish()).isEqualByComparingTo("0.67");
    }

    @Test
    @DisplayName("매진된 DROP과 WISH 기록이 없으면 매진 시간·WISH 대비 판매량은 null이다")
    void own_nullWithoutSoldOutOrWishRecords() {
        OwnSimilarSales own = SimilarSalesCalculator.own(List.of(new SimilarSample(1, 9, 10, 5, false, null, null)),
                Selection.ALL);

        assertThat(own.soldOutRate()).isEqualByComparingTo("0");
        assertThat(own.medianSellOutMinutes()).isNull();
        assertThat(own.soldPerWish()).isNull();
    }

    @Test
    @DisplayName("다른 판매자는 판매자 5명·DROP 10개를 넘을 때만 계산하고 매진 시간은 5분 단위로 반올림한다")
    void others_appliesProtection() {
        // given: 판매자 5명의 DROP 10개, 매진 시간 7분
        List<SimilarSample> enough = IntStream.range(0, 10)
                .mapToObj(i -> new SimilarSample(i, i % 5, 20, 20, true, 7, null)).toList();
        List<SimilarSample> fewSellers = IntStream.range(0, 12)
                .mapToObj(i -> new SimilarSample(i, i % 4, 20, 20, true, 7, null)).toList();

        // when
        OthersSimilarSales available = SimilarSalesCalculator.others(enough, Selection.SIMILAR, Scope.ALL_SELLERS, AS_OF);
        OthersSimilarSales hidden = SimilarSalesCalculator.others(fewSellers, Selection.SIMILAR, Scope.ALL_SELLERS, AS_OF);

        // then
        assertThat(available.dropCountBand()).isEqualTo("10_TO_19");
        assertThat(available.medianSellOutMinutes()).isEqualTo(5);
        assertThat(available.soldOutRate()).isEqualByComparingTo("1.00");
        assertThat(available.available()).isTrue();
        assertThat(hidden.dropCountBand()).isEqualTo("NONE");
        assertThat(hidden.soldOutRate()).isNull();
        assertThat(hidden.available()).isFalse();
        assertThat(hidden.asOf()).isEqualTo(AS_OF);
    }

    @Test
    @DisplayName("다른 판매자 매진 시간은 1분이어도 최소 5분으로 표시하고, 표본 20개 이상은 20_PLUS다")
    void others_minimumMinutesAndBand() {
        List<SimilarSample> samples = IntStream.range(0, 20)
                .mapToObj(i -> new SimilarSample(i, i % 5, 20, 20, true, 1, null)).toList();

        OthersSimilarSales others = SimilarSalesCalculator.others(samples, Selection.CATEGORY, Scope.NEW_SELLERS, AS_OF);

        assertThat(others.medianSellOutMinutes()).isEqualTo(5);
        assertThat(others.dropCountBand()).isEqualTo("20_PLUS");
        assertThat(others.scope()).isEqualTo(Scope.NEW_SELLERS);
    }
}
