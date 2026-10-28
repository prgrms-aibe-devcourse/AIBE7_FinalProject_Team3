package org.example.grab.domain.demand.explanation;

import org.example.grab.domain.demand.explanation.DemandExplanationClient.Generation;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/*
    실제 Gemini를 한 번 호출해 설정·응답 형식을 확인한다. GEMINI_API_KEY가 있을 때만 실행되므로 CI에서는 건너뛴다.
    과금되는 호출이라 평상시 테스트에서는 실행하지 않는다: GEMINI_API_KEY=... ./gradlew test --tests '*DemandExplanationLiveTest'
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@SpringBootTest(properties = "spring.ai.model.chat=google-genai")
@Import(TestcontainersConfiguration.class)
class DemandExplanationLiveTest {

    private static final String INPUT = """
            {"activeWishCount":62,"hoursUntilSaleStart":40,"rangeConfidencePercent":95,
             "conversion":{"own":{"ratePercent":40,"rangeLowPercent":32,"rangeHighPercent":49,"avgQuantityPerBuyer":1.0,
                                  "confidence":"LOW","sampleDropCount":4,"sampleWishCount":120},
                           "market":{"ratePercent":30,"rangeLowPercent":26,"rangeHighPercent":35,"avgQuantityPerBuyer":1.2,
                                     "confidence":"HIGH","sampleDropCountBand":"10_TO_19","sampleWishCountApprox":400}},
             "assumptions":{"conversionRatePercent":30,"conversionRateSource":"MARKET","avgQuantityPerBuyer":1.2,
                            "safetyStockPercent":10},
             "plan":{"low":19,"expected":22,"high":27,"recommended":25,"currentTotalQuantity":60,"currentVsRecommended":{"status":"OVER","gap":35},
                     "options":[{"option":1,"currentQuantity":30,"recommendedQuantity":13},
                                {"option":2,"currentQuantity":30,"recommendedQuantity":12}]},
             "dataGaps":[],"excludesBuyersWithoutWish":true}
            """;

    @Autowired
    private DemandExplanationClient client;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("실제 모델이 형식에 맞는 JSON 설명을 돌려주고 모델명·토큰을 알려 준다")
    void generate_returnsStructuredExplanation() throws IOException {
        assertThat(client.available()).isTrue();

        Generation generation = client.generate(DemandExplanationPrompt.SYSTEM, INPUT);

        boolean valid = DemandExplanationValidator.validate(generation.text(), INPUT, objectMapper).isPresent();
        // 콘솔 인코딩과 관계없이 확인할 수 있게 응답을 UTF-8 파일로 남긴다(build/ 아래라 커밋되지 않는다)
        Files.writeString(Path.of("build", "demand-explanation-live.json"), generation.text(), StandardCharsets.UTF_8);
        System.out.println("model=" + generation.model() + ", tokens=" + generation.tokens()
                + ", validation=" + (valid ? "PASS" : "DISCARD"));
        assertThat(generation.text()).contains("summary");
        assertThat(generation.model()).isNotBlank();
    }
}
