package org.example.grab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
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
            "user,/api/v1/users/me",
            "wish,/api/v1/drops/{dropId}/wish"
    })
    void exposesDomainApiDocs(String group, String path) throws Exception {
        mockMvc.perform(get("/v3/api-docs/" + group))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + path + "']").exists());
    }

    // GR-97: 내 정보 조회의 Authentication 파라미터는 서버가 채우므로 문서 입력란에 나오지 않는다
    @Test
    void hidesAuthenticationParameterOfUserMe() throws Exception {
        mockMvc.perform(get("/v3/api-docs/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get.requestBody").doesNotExist());
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

    /*
        GR-98 회귀: 이름만 분리하고 필드·타입 계약이 그대로인지 고정한다.
        공개 상세·재고는 가용 재고·품절만, 판매자 상세·재고는 기존 수량 계열 필드를 유지한다.
     */
    @Test
    void exposesDropOptionFieldContracts() throws Exception {
        JsonNode schemas = readApiDoc("/v3/api-docs/drop").path("components").path("schemas");

        JsonNode publicDetail = schemas.path("PublicDropDetailOption").path("properties");
        assertThat(propertyNames(publicDetail)).containsExactlyInAnyOrder(
                "optionId", "selections", "unitPrice", "availableStock", "soldOut");
        assertInteger(publicDetail.path("optionId"), "int64");
        assertThat(publicDetail.path("selections").path("type").asText()).isEqualTo("array");
        assertThat(publicDetail.path("selections").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/DropOptionSelectionResponse");
        assertInteger(publicDetail.path("unitPrice"), "int64");
        assertInteger(publicDetail.path("availableStock"), "int32");
        assertThat(publicDetail.path("soldOut").path("type").asText()).isEqualTo("boolean");

        JsonNode publicStock = schemas.path("PublicDropStockOption").path("properties");
        assertThat(propertyNames(publicStock)).containsExactlyInAnyOrder("optionId", "availableStock", "soldOut");
        assertInteger(publicStock.path("optionId"), "int64");
        assertInteger(publicStock.path("availableStock"), "int32");
        assertThat(publicStock.path("soldOut").path("type").asText()).isEqualTo("boolean");

        JsonNode sellerDetail = schemas.path("SellerDropDetailOption").path("properties");
        assertThat(propertyNames(sellerDetail)).containsExactlyInAnyOrder(
                "optionId", "selections", "unitPrice", "totalQuantity", "reservedQuantity",
                "soldQuantity", "active", "sortOrder");
        assertThat(sellerDetail.has("availableStock")).as("판매자 상세는 공개 옵션 스키마로 덮이지 않는다").isFalse();

        JsonNode sellerStock = schemas.path("SellerDropStockOption").path("properties");
        assertThat(propertyNames(sellerStock)).containsExactlyInAnyOrder(
                "optionId", "optionName", "totalStock", "availableStock", "reservedStock", "soldStock", "active");
    }

    /*
        GR-98 회귀: ApiResponse 래퍼의 data를 따라 실제 응답 DTO에 도달했을 때 options.items.$ref가
        각 엔드포인트에 맞는 고유 스키마를 가리키는지 확인한다. 컴포넌트 존재만 보면 잘못된 참조를 놓친다.
     */
    @Test
    void resolvesDropApiPathsToOwnOptionSchema() throws Exception {
        JsonNode doc = readApiDoc("/v3/api-docs/drop");

        Map<String, String> expectedRefs = Map.of(
                "/api/v1/drops/{dropId}", "#/components/schemas/PublicDropDetailOption",
                "/api/v1/drops/{dropId}/stocks", "#/components/schemas/PublicDropStockOption",
                "/api/v1/seller/drops/{dropId}", "#/components/schemas/SellerDropDetailOption",
                "/api/v1/seller/drops/{dropId}/stocks", "#/components/schemas/SellerDropStockOption");

        expectedRefs.forEach((path, optionRef) -> {
            JsonNode response = doc.path("paths").path(path).path("get").path("responses").path("200");
            String wrapperRef = responseSchema(response).path("$ref").asText();
            String dataRef = resolveRef(doc, wrapperRef).path("properties").path("data").path("$ref").asText();
            String actualRef = resolveRef(doc, dataRef).path("properties").path("options").path("items")
                    .path("$ref").asText();
            assertThat(actualRef)
                    .as("%s 200 응답의 options.items.$ref", path)
                    .isEqualTo(optionRef);
        });
    }

    // GR-98 회귀: 도메인별 문서뿐 아니라 전체 /v3/api-docs에서도 네 옵션 스키마가 충돌하지 않는지 확인한다.
    @Test
    void exposesDistinctOptionSchemasInFullApiDocs() throws Exception {
        JsonNode schemas = readApiDoc("/v3/api-docs").path("components").path("schemas");

        assertThat(schemas.has("PublicDropDetailOption")).isTrue();
        assertThat(schemas.has("PublicDropStockOption")).isTrue();
        assertThat(schemas.has("SellerDropDetailOption")).isTrue();
        assertThat(schemas.has("SellerDropStockOption")).isTrue();

        List<String> refs = List.of(
                        "PublicDropDetailResponse", "PublicDropStockResponse",
                        "SellerDropDetailResponse", "SellerDropStockResponse")
                .stream()
                .map(name -> schemas.path(name).path("properties").path("options").path("items").path("$ref").asText())
                .toList();
        assertThat(refs).doesNotHaveDuplicates();
    }

    /*
        GR-99: 내 WISH 목록 200 응답 계약. 래퍼 → data → content.items의 참조를 따라 실제 목록 항목에
        도달해 saleStartsAt과 wishedAt이 각각 string(date-time)으로 노출되는지 확인한다.
        컴포넌트 존재만으로 완료 처리하지 않는다.
     */
    @Test
    @DisplayName("내 WISH 목록의 saleStartsAt·wishedAt이 date-time으로 노출된다")
    void exposesWishListSaleStartsAtContract() throws Exception {
        for (String uri : List.of("/v3/api-docs/wish", "/v3/api-docs")) {
            JsonNode doc = readApiDoc(uri);
            JsonNode response = doc.path("paths").path("/api/v1/users/me/wishes").path("get")
                    .path("responses").path("200");
            JsonNode wrapper = deref(doc, responseSchema(response));
            JsonNode data = deref(doc, wrapper.path("properties").path("data"));
            JsonNode itemProperties = deref(doc, data.path("properties").path("content").path("items"))
                    .path("properties");

            assertThat(itemProperties.path("saleStartsAt").path("type").asText())
                    .as("%s saleStartsAt.type", uri).isEqualTo("string");
            assertThat(itemProperties.path("saleStartsAt").path("format").asText())
                    .as("%s saleStartsAt.format", uri).isEqualTo("date-time");
            assertThat(itemProperties.path("wishedAt").path("type").asText())
                    .as("%s wishedAt.type", uri).isEqualTo("string");
            assertThat(itemProperties.path("wishedAt").path("format").asText())
                    .as("%s wishedAt.format", uri).isEqualTo("date-time");
        }
    }

    // GR-108: 내 정보 조회 200 응답의 provider가 허용값 목록과 함께 노출돼 프론트 생성 타입이 리터럴 유니언이 된다
    @Test
    @DisplayName("내 정보 조회의 provider가 LOCAL·KAKAO·GOOGLE enum으로 노출된다")
    void exposesUserMeProviderEnum() throws Exception {
        JsonNode doc = readApiDoc("/v3/api-docs/user");
        JsonNode response = doc.path("paths").path("/api/v1/users/me").path("get")
                .path("responses").path("200");
        JsonNode wrapper = deref(doc, responseSchema(response));
        JsonNode data = deref(doc, wrapper.path("properties").path("data"));
        JsonNode provider = deref(doc, data.path("properties").path("provider"));

        List<String> values = new ArrayList<>();
        provider.path("enum").forEach(value -> values.add(value.asText()));
        assertThat(provider.path("type").asText()).isEqualTo("string");
        assertThat(values).containsExactly("LOCAL", "KAKAO", "GOOGLE");
    }

    private JsonNode readApiDoc(String uri) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(uri))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    // 컨트롤러가 produces를 지정하지 않아 생성 문서의 미디어 타입은 */* 이다. 첫 content 항목의 schema를 쓴다.
    private static JsonNode responseSchema(JsonNode response) {
        JsonNode content = response.path("content");
        if (content.has("application/json")) {
            return content.path("application/json").path("schema");
        }
        return content.elements().hasNext() ? content.elements().next().path("schema") : content;
    }

    private static JsonNode resolveRef(JsonNode doc, String ref) {
        assertThat(ref).as("$ref").startsWith("#/");
        JsonNode node = doc;
        for (String token : ref.substring(2).split("/")) {
            node = node.path(token);
        }
        return node;
    }

    private static JsonNode deref(JsonNode doc, JsonNode node) {
        return node.has("$ref") ? resolveRef(doc, node.path("$ref").asText()) : node;
    }

    private static List<String> propertyNames(JsonNode properties) {
        List<String> names = new ArrayList<>();
        properties.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertInteger(JsonNode node, String format) {
        assertThat(node.path("type").asText()).isEqualTo("integer");
        assertThat(node.path("format").asText()).isEqualTo(format);
    }
}
