package org.example.grab.global.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("오류 응답은 success false, null인 data, error로 직렬화된다")
    // COMMON.md 1.5 공통 오류 응답의 최상위 계약을 확인하는 테스트
    void serializesErrorResponse() {
        // given
        ErrorResponse response = ErrorResponse.of("VALIDATION_FAILED", "입력값이 올바르지 않습니다.", List.of());

        // when
        JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(response));

        // then
        // data는 생략되지 않고 null로 포함되어야 함
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("success", "data", "error");
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("data").isNull()).isTrue();
        assertThat(json.get("error").isObject()).isTrue();
    }
}
