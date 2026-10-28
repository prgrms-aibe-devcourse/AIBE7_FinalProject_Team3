package org.example.grab.domain.demand.explanation;

import org.example.grab.domain.demand.dto.ConversionBasis;
import org.example.grab.domain.demand.dto.ConversionConfidence;
import org.example.grab.domain.demand.dto.SimilarSalesResponse;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Basis;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.OthersSimilarSales;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.OwnSimilarSales;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Reason;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Scope;
import org.example.grab.domain.demand.dto.SimilarSalesResponse.Selection;
import org.example.grab.domain.demand.dto.StockDataGap;
import org.example.grab.domain.demand.dto.StockRecommendationRequest.OptionInput;
import org.example.grab.domain.demand.dto.StockRecommendationResponse;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.MarketSales;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.OptionRecommendation;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.OwnSales;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.PriceBand;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.PriceBandType;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.PricePosition;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.PriceReference;
import org.example.grab.domain.demand.dto.StockRecommendationResponse.Recommendation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockExplanationPromptTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-10-28T00:00:00+09:00");
    static final List<OptionInput> OPTIONS = List.of(new OptionInput(12000L, 30), new OptionInput(12000L, 30));

    @Test
    @DisplayName("재고 추천 집계·옵션 번호·입력 수량 비교·비슷한 DROP 실적을 정수 %로 담고 시각은 넣지 않는다")
    void input_containsRecommendationAndSimilarSales() {
        String input = StockExplanationPrompt.input(recommendation(), similarSales(), OPTIONS, OBJECT_MAPPER);
        JsonNode tree = OBJECT_MAPPER.readTree(input);

        assertThat(input).doesNotContain("calculatedAt", "asOf", "calculationVersion", "E+");
        assertThat(tree.at("/referencePrice").asLong()).isEqualTo(12000);
        assertThat(tree.at("/sales/own/sellThroughPercent").decimalValue()).isEqualByComparingTo("82");
        assertThat(tree.at("/sales/market/sampleDropCountBand").asString()).isEqualTo("10_TO_19");
        assertThat(tree.at("/basis").asString()).isEqualTo("MARKET");
        assertThat(tree.at("/recommendation/safetyStockPercent").decimalValue()).isEqualByComparingTo("10");
        assertThat(tree.at("/recommendation/options/1/option").asInt()).isEqualTo(2);
        assertThat(tree.at("/recommendation/options/1/currentQuantity").asInt()).isEqualTo(30);
        assertThat(tree.at("/recommendation/options/1/recommendedQuantity").asInt()).isEqualTo(20);
        // 입력 60개는 준비 권장 44개보다 16개 많다
        assertThat(tree.at("/current/totalQuantity").asInt()).isEqualTo(60);
        assertThat(tree.at("/current/vsRecommended/status").asString()).isEqualTo("OVER");
        assertThat(tree.at("/current/vsRecommended/gap").asInt()).isEqualTo(16);
        assertThat(tree.at("/priceReference/bands/0/sellThroughPercent").decimalValue()).isEqualByComparingTo("78");
        assertThat(tree.at("/similarSales/own/medianSellOutMinutes").asInt()).isEqualTo(12);
        assertThat(tree.at("/similarSales/others/soldOutPercent").decimalValue()).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("옵션 수량을 다 입력하지 않았으면 입력 수량 비교를 넣지 않는다")
    void input_skipsCurrentWhenQuantityMissing() {
        List<OptionInput> partial = Arrays.asList(new OptionInput(12000L, 30), new OptionInput(12000L, null));

        JsonNode tree = OBJECT_MAPPER.readTree(
                StockExplanationPrompt.input(recommendation(), similarSales(), partial, OBJECT_MAPPER));

        assertThat(tree.get("current").isNull()).isTrue();
        assertThat(tree.at("/recommendation/options/1/currentQuantity").isNull()).isTrue();
    }

    @Test
    @DisplayName("표본이 부족해 추천하지 못했으면 기준·수량은 null이고 이유(dataGaps)와 보호 단위 미달 실적은 비운다")
    void input_explainsMissingRecommendation() {
        StockRecommendationResponse none = new StockRecommendationResponse(AS_OF, "s1", 12000,
                new OwnSales(null, null, null, null, null, ConversionConfidence.NONE, 1),
                MarketSales.unavailable(AS_OF), null,
                new Recommendation(null, null, null, null, new BigDecimal("0.1"),
                        List.of(new OptionRecommendation(0, null), new OptionRecommendation(1, null))),
                new PriceReference(PricePosition.UNKNOWN, null, null, List.of()),
                List.of(StockDataGap.NO_SALES_SAMPLE));
        SimilarSalesResponse firstSale = new SimilarSalesResponse(null, Reason.FIRST_SALE, null,
                new OthersSimilarSales(Selection.CATEGORY, Scope.ALL_SELLERS, "NONE", null, null, null, null, AS_OF));

        JsonNode tree = OBJECT_MAPPER.readTree(StockExplanationPrompt.input(none, firstSale, OPTIONS, OBJECT_MAPPER));

        assertThat(tree.get("basis").isNull()).isTrue();
        assertThat(tree.at("/recommendation/recommended").isNull()).isTrue();
        assertThat(tree.at("/current/vsRecommended").isNull()).isTrue();
        assertThat(tree.at("/dataGaps/0").asString()).isEqualTo("NO_SALES_SAMPLE");
        assertThat(tree.at("/similarSales/own").isNull()).isTrue();
        assertThat(tree.at("/similarSales/others").isNull()).isTrue();
    }

    // StockExplanationServiceTest도 같은 값으로 입력을 만든다
    static StockRecommendationResponse recommendation() {
        return new StockRecommendationResponse(AS_OF, "s1", 12000,
                new OwnSales(32, 41, 55, new BigDecimal("0.82"), new BigDecimal("0.25"), ConversionConfidence.LOW, 4),
                new MarketSales(25, 40, 60, new BigDecimal("0.71"), new BigDecimal("0.38"), ConversionConfidence.HIGH,
                        "10_TO_19", AS_OF),
                ConversionBasis.MARKET,
                new Recommendation(25, 40, 60, 44, new BigDecimal("0.1"),
                        List.of(new OptionRecommendation(0, 24), new OptionRecommendation(1, 20))),
                new PriceReference(PricePosition.MID, 9000L, 15000L, List.of(
                        new PriceBand(PriceBandType.LOWER, new BigDecimal("0.78"), "10_TO_19"),
                        new PriceBand(PriceBandType.SIMILAR, new BigDecimal("0.71"), "20_PLUS"))),
                List.of());
    }

    static SimilarSalesResponse similarSales() {
        return new SimilarSalesResponse(Basis.OWN, Reason.OWN_ENOUGH,
                new OwnSimilarSales(Selection.SIMILAR, 3, new BigDecimal("0.67"), 12, new BigDecimal("0.88"),
                        new BigDecimal("0.55")),
                new OthersSimilarSales(Selection.SIMILAR, Scope.ALL_SELLERS, "10_TO_19", new BigDecimal("0.40"), 15,
                        new BigDecimal("0.81"), null, AS_OF));
    }
}
