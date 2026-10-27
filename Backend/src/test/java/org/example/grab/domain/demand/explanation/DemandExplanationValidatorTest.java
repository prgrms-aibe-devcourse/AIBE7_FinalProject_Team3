package org.example.grab.domain.demand.explanation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DemandExplanationValidatorTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final String INPUT = """
            {"activeWishCount":1740,"rangeConfidencePercent":95,
             "assumptions":{"conversionRatePercent":30,"avgQuantityPerBuyer":1.2,"safetyStockPercent":10},
             "plan":{"expected":126,"recommended":139,"differenceFromCurrent":-35,
                     "options":[{"option":1,"recommendedQuantity":70}]}}
            """;

    @Test
    @DisplayName("문장 속 숫자가 모두 입력값이면 설명을 통과시킨다(천 단위 쉼표·소수 표기·음수의 절댓값 포함)")
    void validate_acceptsNumbersFromInput() {
        String response = """
                {"summary":"활성 WISH 1,740명에 전환율 30%를 적용한 예상 판매량은 126개입니다.",
                 "reasons":["구매자당 1.20개, 안전 재고 10%를 더해 준비 권장 수량은 139개입니다."],
                 "risks":["현재 준비 수량이 권장보다 35개 많습니다."],
                 "dataGaps":[],
                 "notRecommended":["옵션 1을 70개보다 적게 준비하면 95% 구간 하단에 못 미칠 수 있습니다."]}
                """;

        assertThat(DemandExplanationValidator.validate(response, INPUT, OBJECT_MAPPER))
                .hasValueSatisfying(explanation -> {
                    assertThat(explanation.summary()).startsWith("활성 WISH 1,740명");
                    assertThat(explanation.reasons()).hasSize(1);
                    assertThat(explanation.dataGaps()).isEmpty();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 입력에 없는 숫자(새로 계산한 값)
            "{\"summary\":\"예상 판매량은 127개입니다.\",\"reasons\":[],\"risks\":[],\"dataGaps\":[],\"notRecommended\":[]}",
            // 시간을 일로 바꾸는 등 단위를 바꾼 숫자
            "{\"summary\":\"판매 시작까지 2일 남았습니다.\",\"reasons\":[],\"risks\":[],\"dataGaps\":[],\"notRecommended\":[]}",
            // 형식 위반: summary 없음, 배열이 아님, JSON 아님
            "{\"reasons\":[\"근거\"]}",
            "{\"summary\":\"요약\",\"reasons\":\"근거\"}",
            "설명: 예상 판매량은 126개입니다."
    })
    @DisplayName("입력에 없는 숫자가 있거나 형식이 다르면 설명을 버린다")
    void validate_discardsInvalidResponse(String response) {
        assertThat(DemandExplanationValidator.validate(response, INPUT, OBJECT_MAPPER)).isEmpty();
    }

    @Test
    @DisplayName("항목 수·문장 길이 제한을 넘으면 버린다")
    void validate_discardsOversizedResponse() {
        String tooMany = "{\"summary\":\"요약\",\"reasons\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}";
        String tooLong = "{\"summary\":\"" + "가".repeat(DemandExplanationValidator.MAX_TEXT_LENGTH + 1) + "\"}";

        assertThat(DemandExplanationValidator.validate(tooMany, INPUT, OBJECT_MAPPER)).isEmpty();
        assertThat(DemandExplanationValidator.validate(tooLong, INPUT, OBJECT_MAPPER)).isEmpty();
    }

    @Test
    @DisplayName("문장에서 쉼표가 들어간 수와 소수를 하나의 값으로 읽는다")
    void numbers_parsesGroupedAndDecimalNumbers() {
        assertThat(DemandExplanationValidator.numbers("WISH 1,740명, 1.2개, 30% 중 옵션 2"))
                .containsExactly(new BigDecimal("1740"), new BigDecimal("1.2"), new BigDecimal("30"),
                        new BigDecimal("2"));
        assertThat(DemandExplanationValidator.numbers("숫자 없음")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("시장 표본 DROP 수 구간 코드의 끝값은 허용하고, 다른 문자열 속 숫자는 허용하지 않는다")
    void validate_acceptsMarketBandBounds() {
        String input = """
                {"conversion":{"market":{"ratePercent":30,"sampleDropCountBand":"10_TO_19"},
                               "own":{"sampleDropCountBand":"20_PLUS","note":"옵션 250mm"}}}
                """;

        assertThat(DemandExplanationValidator.validate(
                "{\"summary\":\"시장 평균은 DROP 10~19개 표본의 전환율 30%입니다.\"}", input, OBJECT_MAPPER))
                .isPresent();
        assertThat(DemandExplanationValidator.validate(
                "{\"summary\":\"DROP 20개 이상을 비교했습니다.\"}", input, OBJECT_MAPPER))
                .isPresent();
        assertThat(DemandExplanationValidator.validate(
                "{\"summary\":\"250mm 옵션을 권장합니다.\"}", input, OBJECT_MAPPER))
                .isEmpty();
    }
}
