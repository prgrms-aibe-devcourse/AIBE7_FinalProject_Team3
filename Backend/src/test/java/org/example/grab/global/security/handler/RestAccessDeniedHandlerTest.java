package org.example.grab.global.security.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

// M03-07: Security 인가 규칙에 막힌 요청을 403 ACCESS_DENIED 공통 오류 형식으로 응답한다
class RestAccessDeniedHandlerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RestAccessDeniedHandler handler =
            new RestAccessDeniedHandler(new SecurityErrorResponseWriter(jsonMapper));

    @Test
    @DisplayName("인가 거부는 공통 오류 형식의 403 ACCESS_DENIED")
    void respondsAccessDenied() throws Exception {
        // given
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        handler.handle(new MockHttpServletRequest("GET", "/api/v1/seller/drops"), response,
                new AccessDeniedException("Access Denied"));

        // then
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("error").get("code").asString()).isEqualTo("ACCESS_DENIED");
        assertThat(body.get("error").get("message").asString()).isEqualTo("접근 권한이 없습니다.");
    }
}
