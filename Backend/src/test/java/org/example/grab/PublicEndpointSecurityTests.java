package org.example.grab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// SecurityConfig가 적용된 실제 필터 체인으로 공개 조회가 비로그인에 열려 있는지 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
class PublicEndpointSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("비로그인 GET /api/v1/categories는 200")
    void exposesCategoriesWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("비로그인 GET /api/v1/drops는 200")
    void exposesPublicDropsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/drops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("판매자 DROP 경로는 여전히 401")
    void keepsSellerDropRoutesProtected() throws Exception {
        mockMvc.perform(get("/api/v1/seller/drops").header("Accept", "application/json"))
                .andExpect(status().isUnauthorized());
    }
}
