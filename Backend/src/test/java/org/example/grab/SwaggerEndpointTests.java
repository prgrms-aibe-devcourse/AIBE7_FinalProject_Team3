package org.example.grab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SwaggerEndpointTests {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesApiDocsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"openapi\"")));
    }

    @ParameterizedTest
    @CsvSource({
            "category,/api/v1/categories",
            "dashboard,/api/v1/seller/dashboard/summary",
            "drop,/api/v1/drops",
            "order,/api/v1/orders",
            "payment,/api/v1/orders/{orderId}/payments",
            "user,/api/v1/auth/email-verification",
            "user,/api/v1/auth/csrf",
            "wish,/api/v1/drops/{dropId}/wish"
    })
    void exposesDomainApiDocs(String group, String path) throws Exception {
        mockMvc.perform(get("/v3/api-docs/" + group))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + path + "']").exists());
    }

    @Test
    void exposesSwaggerUiWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    /*
        공개 DROP 스키마 계약(GR-96). 목록·상세의 brandName과 목록 availableStock 타입, null 허용 표현,
        그리고 목록 status enum 제한과 상세 status enum 보존을 실제 /v3/api-docs/drop에서 고정한다.
        OpenAPI 3.1은 null 허용을 type 배열의 "null"로 표현하므로 값 포함 여부로 검사한다.
     */
    @Test
    void exposesPublicDropSchemaContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs/drop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.PublicDropListResponse.properties.brandName.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.PublicDropListResponse.properties.availableStock.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.PublicDropListResponse.properties.availableStock.format")
                        .value("int64"))
                .andExpect(jsonPath("$.components.schemas.PublicDropListResponse.properties.thumbnailUrl.type",
                        hasItems("string", "null")))
                .andExpect(jsonPath("$.components.schemas.PublicDropListResponse.properties.minPrice.type",
                        hasItems("integer", "null")))
                .andExpect(jsonPath("$.components.schemas.PublicDropListResponse.properties.status.enum",
                        contains("WISH", "GRAB", "ENDED")))
                .andExpect(jsonPath("$.components.schemas.PublicDropDetailResponse.properties.brandName.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.PublicDropDetailResponse.properties.minPrice.type",
                        hasItems("integer", "null")))
                .andExpect(jsonPath("$.components.schemas.PublicDropDetailResponse.properties.status.enum",
                        hasItems("CANCELED", "DRAFT")));
    }

    /*
        GR-98: 공개 상세·재고와 판매자 상세·재고 DTO가 각각 중첩 record Option을 가져 스키마 이름이 충돌한다.
        /v3/api-docs/drop에서 네 응답의 options.items.$ref가 서로 다른 고유 스키마를 가리키는지 고정한다.
        컴포넌트 존재만 보지 않고 각 응답 스키마의 실제 참조를 확인한다.
     */
    @Test
    void exposesDistinctOptionSchemasPerDropResponse() throws Exception {
        JsonNode doc = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs/drop"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        Map<String, String> expectedRefs = Map.of(
                "PublicDropDetailResponse", "#/components/schemas/PublicDropDetailOption",
                "PublicDropStockResponse", "#/components/schemas/PublicDropStockOption",
                "SellerDropDetailResponse", "#/components/schemas/SellerDropDetailOption",
                "SellerDropStockResponse", "#/components/schemas/SellerDropStockOption");

        expectedRefs.forEach((responseSchema, expectedRef) -> {
            String actualRef = doc.path("components").path("schemas").path(responseSchema)
                    .path("properties").path("options").path("items").path("$ref").asText();
            assertThat(actualRef)
                    .as("%s.options.items.$ref", responseSchema)
                    .isEqualTo(expectedRef);
        });

        assertThat(expectedRefs.values()).doesNotHaveDuplicates();
    }
}
