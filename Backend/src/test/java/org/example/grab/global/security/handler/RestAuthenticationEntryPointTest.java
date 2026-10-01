package org.example.grab.global.security.handler;

import org.example.grab.global.security.jwt.InvalidAccessTokenException;
import org.example.grab.global.security.jwt.InvalidAccessTokenException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

// M03-07: 인증 실패를 예외 종류에 따라 401 AUTHENTICATION_REQUIRED·INVALID_TOKEN 공통 오류 형식으로 응답한다
class RestAuthenticationEntryPointTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RestAuthenticationEntryPoint entryPoint =
            new RestAuthenticationEntryPoint(new SecurityErrorResponseWriter(jsonMapper));

    @Test
    @DisplayName("토큰 검증 실패는 401 INVALID_TOKEN")
    void respondsInvalidTokenForInvalidAccessToken() throws Exception {
        // given
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        entryPoint.commence(new MockHttpServletRequest(), response, new InvalidAccessTokenException(Reason.EXPIRED));

        // then
        assertThat(response.getStatus()).isEqualTo(401);
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.get("error").get("code").asString()).isEqualTo("INVALID_TOKEN");
        // 운영 로그용 사유는 응답에 노출하지 않는다
        assertThat(response.getContentAsString()).doesNotContain("EXPIRED");
    }

    @Test
    @DisplayName("인증 정보 없이 인가 단계에서 넘어오면 공통 오류 형식의 401 AUTHENTICATION_REQUIRED")
    void respondsAuthenticationRequiredOtherwise() throws Exception {
        // given
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        entryPoint.commence(new MockHttpServletRequest(), response,
                new InsufficientAuthenticationException("Full authentication is required"));

        // then
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("data").isNull()).isTrue();
        assertThat(body.get("error").get("code").asString()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(body.get("error").get("message").asString()).isEqualTo("인증이 필요합니다.");
        assertThat(body.get("error").get("fieldErrors").isEmpty()).isTrue();
    }
}
