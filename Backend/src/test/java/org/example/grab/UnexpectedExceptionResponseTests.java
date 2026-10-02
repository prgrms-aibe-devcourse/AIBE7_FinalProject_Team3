package org.example.grab;

import org.example.grab.domain.category.service.CategoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/*
    GR-33 M03-03: 인증 없는 공개 경로에서 서버 장애가 401이 아니라 500으로 응답하는지 확인한다.
    처리하지 못한 예외가 /error로 error dispatch되면 Security가 익명 요청을 401로 막았다.
    MockMvc는 error dispatch를 하지 않아 이 문제를 재현하지 못하므로 실제 서버에 HTTP로 요청한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnexpectedExceptionResponseTests {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @MockitoBean
    private CategoryService categoryService;

    @Test
    @DisplayName("비로그인 공개 경로에서 Redis 장애가 나면 401이 아니라 500 INTERNAL_SERVER_ERROR로 응답한다")
    void respondsWithInternalServerErrorForAnonymousRequest() throws Exception {
        // given
        given(categoryService.findActiveCategories()).willThrow(new RedisConnectionFailureException("Redis 연결 실패"));

        // when
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/categories")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // then
        assertThat(response.statusCode()).isEqualTo(500);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("error").get("code").asString()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.body()).doesNotContain("Redis 연결 실패");
    }
}
