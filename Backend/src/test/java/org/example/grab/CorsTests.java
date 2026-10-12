package org.example.grab;

import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-44 M02-03: 허용한 프론트엔드 Origin(기본값 http://localhost:5173)만 쿠키를 포함한 CORS 요청을 할 수 있는지 실제 필터 체인으로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorsTests {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String OTHER_ORIGIN = "https://evil.example";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("허용 Origin의 preflight는 그 Origin과 Credential, CSRF 헤더를 허용한다")
    void allowsPreflightFromAllowedOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/auth/signup")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("x-xsrf-token")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")));
    }

    @Test
    @DisplayName("허용 Origin의 실제 요청 응답에는 그 Origin과 Credential 허용 헤더가 있다")
    void allowsActualRequestFromAllowedOrigin() throws Exception {
        mockMvc.perform(get("/api/v1/categories").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("허용하지 않은 Origin은 preflight와 실제 요청 모두 403이고 CORS 허용 헤더가 없다")
    void rejectsOtherOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/auth/signup")
                        .header(HttpHeaders.ORIGIN, OTHER_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        // CSRF 토큰이 있어도 CORS가 먼저 거부한다
        mockMvc.perform(post("/api/v1/auth/signup").with(csrf()).header(HttpHeaders.ORIGIN, OTHER_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mockMvc.perform(get("/api/v1/categories").header(HttpHeaders.ORIGIN, OTHER_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("같은 출처 요청은 CORS 대상이 아니라 그대로 처리된다")
    void ignoresSameOriginRequest() throws Exception {
        // MockMvc 요청의 출처는 http://localhost다. 운영에서 Nginx 뒤 같은 출처로 서비스하는 경우와 같다
        mockMvc.perform(get("/api/v1/categories").header(HttpHeaders.ORIGIN, "http://localhost"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
