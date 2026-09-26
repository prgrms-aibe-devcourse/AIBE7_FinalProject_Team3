package org.example.grab.global.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("성공 응답은 success true, 전달한 data, null인 message로 직렬화된다")
    // COMMON.md 1.4 공통 성공 응답 계약을 확인하는 테스트
    void serializesSuccessResponse() {
        // given
        ApiResponse<Map<String, Object>> response = ApiResponse.success(Map.of("userId", 1));

        // when
        // ApiResponse 객체를 JSON 문자열로 변경 후 JsonNode 트리로 만듦
        // {"success":true,"data":{"userId":1},"message":null}
        JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(response));

        // then
        // message는 생략되지 않고 null로 포함되어야 함
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("success", "data", "message");
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("data").get("userId").asInt()).isEqualTo(1);
        assertThat(json.get("message").isNull()).isTrue();
    }
}
