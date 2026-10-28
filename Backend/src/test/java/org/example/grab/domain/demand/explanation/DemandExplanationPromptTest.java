package org.example.grab.domain.demand.explanation;

import org.example.grab.domain.demand.dto.AssumptionSource;
import org.example.grab.domain.demand.dto.ConversionConfidence;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Assumption;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Assumptions;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Conversion;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Demand;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.MarketConversion;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.OptionPlan;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.OwnConversion;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Plan;
import org.example.grab.domain.demand.dto.DemandAnalysisResponse.Suggestion;
import org.example.grab.domain.demand.dto.DemandDataGap;
import org.example.grab.domain.demand.dto.SampleSelection;
import org.example.grab.domain.demand.dto.SimilarSalesResponse;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Basis;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.OthersSimilarSales;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.OwnSimilarSales;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Reason;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Scope;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Selection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DemandExplanationPromptTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("입력은 집계값·코드값만 담고 비율은 정수 %로, 옵션은 번호로 바꾼다")
    void input_containsOnlyAggregatesAndCodes() {
        // when
        String input = DemandExplanationPrompt.input(analysis(), similarSales(), OBJECT_MAPPER);
        JsonNode tree = OBJECT_MAPPER.readTree(input);

        // then: 판매자가 입력한 옵션명·DROP ID·기준 시각은 없다
        assertThat(input).doesNotContain("블랙", "화이트", "\"dropId\"", "asOf", "E+");
        assertThat(tree.at("/conversion/own/ratePercent").decimalValue()).isEqualByComparingTo("40");
        assertThat(tree.at("/conversion/market/rangeLowPercent").decimalValue()).isEqualByComparingTo("27");
        assertThat(tree.at("/conversion/market/sampleDropCountBand").asString()).isEqualTo("10_TO_19");
        assertThat(tree.at("/assumptions/conversionRateSource").asString()).isEqualTo("MARKET");
        assertThat(tree.at("/assumptions/safetyStockPercent").decimalValue()).isEqualByComparingTo("10");
        assertThat(tree.at("/plan/options/1/option").asInt()).isEqualTo(2);
        assertThat(tree.at("/plan/options/1/recommendedQuantity").asInt()).isEqualTo(69);
        assertThat(tree.at("/dataGaps/0").asString()).isEqualTo("SMALL_CONVERSION_SAMPLE");
        assertThat(tree.at("/rangeConfidencePercent").asInt()).isEqualTo(95);
        assertThat(tree.at("/plan/currentVsRecommended/status").asString()).isEqualTo("SHORT");
        assertThat(tree.at("/plan/currentVsRecommended/gap").asInt()).isEqualTo(19);
        assertThat(tree.at("/plan/differenceFromCurrent").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("시장 평균이 기준에 미달하면 값 없이 신뢰도 NONE만 전달한다")
    void input_keepsUnavailableMarketEmpty() {
        DemandAnalysisResponse base = analysis();
        DemandAnalysisResponse analysis = new DemandAnalysisResponse(base.dropId(), base.calculatedAt(),
                base.calculationVersion(), base.suggestion(), base.demand(),
                new Conversion(SampleSelection.CATEGORY, base.conversion().own(),
                        MarketConversion.unavailable(OffsetDateTime.parse("2026-10-22T00:00:00+09:00"))),
                base.assumptions(), base.plan(), null, base.dataGaps(), base.notes());

        JsonNode market = OBJECT_MAPPER.readTree(DemandExplanationPrompt.input(analysis, similarSales(), OBJECT_MAPPER))
                .at("/conversion/market");

        assertThat(market.get("ratePercent").isNull()).isTrue();
        assertThat(market.get("sampleWishCountApprox").isNull()).isTrue();
        assertThat(market.get("confidence").asString()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("비슷한 DROP 실적은 기준·이유와 내·다른 판매자 집계를 정수 %·분으로 담는다(GR-169)")
    void input_containsSimilarSales() {
        JsonNode similar = OBJECT_MAPPER.readTree(DemandExplanationPrompt.input(analysis(), similarSales(), OBJECT_MAPPER))
                .at("/similarSales");

        assertThat(similar.get("basis").asString()).isEqualTo("OTHERS");
        assertThat(similar.get("reason").asString()).isEqualTo("OWN_FEW");
        assertThat(similar.at("/own/selection").asString()).isEqualTo("SIMILAR");
        assertThat(similar.at("/own/sampleDropCount").asInt()).isEqualTo(2);
        assertThat(similar.at("/own/soldOutPercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(similar.at("/own/medianSellOutMinutes").asInt()).isEqualTo(12);
        assertThat(similar.at("/own/soldPerWish").isNull()).isTrue();
        assertThat(similar.at("/others/scope").asString()).isEqualTo("ALL_SELLERS");
        assertThat(similar.at("/others/sampleDropCountBand").asString()).isEqualTo("10_TO_19");
        assertThat(similar.at("/others/sellThroughPercent").decimalValue()).isEqualByComparingTo("81");
        assertThat(similar.at("/others/asOf").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("다른 판매자 실적이 보호 단위에 못 미치거나 내 종료 DROP이 없으면 null로 둔다")
    void input_leavesUnavailableSimilarSalesNull() {
        SimilarSalesResponse firstSale = new SimilarSalesResponse(null, Reason.FIRST_SALE, null,
                new OthersSimilarSales(Selection.CATEGORY, Scope.ALL_SELLERS, "NONE", null, null, null, null,
                        OffsetDateTime.parse("2026-10-22T00:00:00+09:00")));

        JsonNode similar = OBJECT_MAPPER.readTree(DemandExplanationPrompt.input(analysis(), firstSale, OBJECT_MAPPER))
                .at("/similarSales");

        assertThat(similar.get("basis").isNull()).isTrue();
        assertThat(similar.get("own").isNull()).isTrue();
        assertThat(similar.get("others").isNull()).isTrue();
    }

    // DemandExplanationServiceTest도 같은 비슷한 DROP 실적으로 입력을 만든다
    static SimilarSalesResponse similarSales() {
        return new SimilarSalesResponse(Basis.OTHERS, Reason.OWN_FEW,
                new OwnSimilarSales(Selection.SIMILAR, 2, new BigDecimal("0.50"), 12, new BigDecimal("0.88"), null),
                new OthersSimilarSales(Selection.SIMILAR, Scope.ALL_SELLERS, "10_TO_19", new BigDecimal("0.40"), 15,
                        new BigDecimal("0.81"), new BigDecimal("0.35"), OffsetDateTime.parse("2026-10-22T00:00:00+09:00")));
    }

    // DemandExplanationServiceTest도 같은 분석 결과로 입력을 만든다
    static DemandAnalysisResponse analysis() {
        return new DemandAnalysisResponse(
                100L, OffsetDateTime.parse("2026-10-22T14:00:00+09:00"), "v1", new Suggestion(true),
                new Demand(420, 40, List.of(), true),
                new Conversion(SampleSelection.CATEGORY,
                        new OwnConversion(new BigDecimal("0.40"), new BigDecimal("0.30"), new BigDecimal("0.51"),
                                new BigDecimal("1.0"), ConversionConfidence.LOW, 3, 85),
                        new MarketConversion(new BigDecimal("0.30"), new BigDecimal("0.27"), new BigDecimal("0.33"),
                                new BigDecimal("1.0"), ConversionConfidence.HIGH, "10_TO_19", 1740L,
                                OffsetDateTime.parse("2026-10-22T00:00:00+09:00"))),
                new Assumptions(
                        new Assumption(new BigDecimal("0.30"), AssumptionSource.MARKET),
                        new Assumption(new BigDecimal("1.0"), AssumptionSource.MARKET),
                        new Assumption(new BigDecimal("0.1"), AssumptionSource.DEFAULT)),
                new Plan(113, 126, 139, 139, 120, 19, List.of(
                        new OptionPlan(1001L, "블랙 / M", 60, 70),
                        new OptionPlan(1002L, "화이트 / M", 60, 69))),
                null,
                List.of(DemandDataGap.SMALL_CONVERSION_SAMPLE),
                List.of("WISH 없이 구매한 사용자는 전환율에 포함하지 않았습니다."));
    }
}
