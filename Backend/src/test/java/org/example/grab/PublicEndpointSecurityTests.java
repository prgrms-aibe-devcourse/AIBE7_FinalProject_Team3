package org.example.grab;

import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// SecurityConfig가 적용된 실제 필터 체인으로 공개 조회가 비로그인에 열려 있는지 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
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
    @DisplayName("비로그인 GET /api/v1/drops/{dropId}는 인증을 요구하지 않는다")
    void exposesPublicDropDetailWithoutAuthentication() throws Exception {
        // 존재하지 않는 id로 조회해도 401/302가 아니라 404(DROP_NOT_FOUND)까지 도달해야 개방된 것이다.
        mockMvc.perform(get("/api/v1/drops/999999999").header("Accept", "application/json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DROP_NOT_FOUND"));
    }

    @Test
    @DisplayName("판매자 DROP 경로는 여전히 401")
    void keepsSellerDropRoutesProtected() throws Exception {
        mockMvc.perform(get("/api/v1/seller/drops").header("Accept", "application/json"))
                .andExpect(status().isUnauthorized());
    }

    // GR-16은 GET /api/v1/drops만 permitAll로 열었다. 아래 두 API는 인증이 필요하다.
    // CSRF는 GR-44 전까지 임시 해제 상태지만, 재활성화 후에도 인가 단계까지 가도록 csrf()로 토큰을 채워
    // 인증 요구(401)를 확인한다. 추후 matcher가 /api/v1/drops/** 로
    // 넓어져 이 경로가 permitAll이 되면 이 테스트가 깨져야 한다.
    @Test
    @DisplayName("비로그인 PUT /api/v1/drops/{dropId}/wish는 401")
    void keepsWishRegistrationProtected() throws Exception {
        mockMvc.perform(put("/api/v1/drops/1/wish").with(csrf()).header("Accept", "application/json"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비로그인 DELETE /api/v1/drops/{dropId}/wish는 401")
    void keepsWishCancelProtected() throws Exception {
        mockMvc.perform(delete("/api/v1/drops/1/wish").with(csrf()).header("Accept", "application/json"))
                .andExpect(status().isUnauthorized());
    }
}
