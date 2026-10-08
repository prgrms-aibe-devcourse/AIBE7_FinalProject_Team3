package org.example.grab;

import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
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
}
