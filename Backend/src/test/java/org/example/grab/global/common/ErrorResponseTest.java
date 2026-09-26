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

    @Test
    @DisplayName("error는 code, message, fieldErrors로 직렬화된다")
    // COMMON.md 1.5 error 내부 계약을 확인하는 테스트
    void serializesErrorDetail() {
        // given
        ErrorResponse response = ErrorResponse.of("INVALID_EMAIL", "이메일 형식이 올바르지 않습니다.", List.of());

        // when
        JsonNode error = jsonMapper.readTree(jsonMapper.writeValueAsString(response)).get("error");

        // then
        assertThat(error.propertyNames()).containsExactlyInAnyOrder("code", "message", "fieldErrors");
        assertThat(error.get("code").asString()).isEqualTo("INVALID_EMAIL");
        assertThat(error.get("message").asString()).isEqualTo("이메일 형식이 올바르지 않습니다.");
        assertThat(error.get("fieldErrors").isArray()).isTrue();
    }

    @Test
    @DisplayName("필드 오류가 없으면 fieldErrors는 null이 아닌 빈 배열로 직렬화된다")
    // COMMON.md 1.5 "해당 사항이 없으면 빈 배열" 규칙을 확인하는 테스트
    void serializesEmptyFieldErrorsAsEmptyArray() {
        // given
        ErrorResponse response = ErrorResponse.of("INVALID_REQUEST", "요청 형식이 올바르지 않습니다.", List.of());

        // when
        JsonNode fieldErrors = jsonMapper.readTree(jsonMapper.writeValueAsString(response))
                .get("error").get("fieldErrors");

        // then
        assertThat(fieldErrors.isArray()).isTrue();
        assertThat(fieldErrors.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("fieldErrors로 null을 전달해도 빈 배열로 직렬화된다")
    // 호출부가 null을 넘겨도 오류 응답이 명세의 빈 배열 규칙을 지키는지 확인하는 테스트
    void serializesNullFieldErrorsAsEmptyArray() {
        // given
        ErrorResponse response = ErrorResponse.of("INVALID_REQUEST", "요청 형식이 올바르지 않습니다.", null);

        // when
        JsonNode fieldErrors = jsonMapper.readTree(jsonMapper.writeValueAsString(response))
                .get("error").get("fieldErrors");

        // then
        assertThat(fieldErrors.isArray()).isTrue();
        assertThat(fieldErrors.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("필드 오류 하나는 field와 reason만 포함하고 거부된 입력값을 포함하지 않는다")
    // 비밀번호 등 입력 원문이 응답에 노출되지 않도록 필드 오류의 키를 확인하는 테스트
    void serializesFieldErrorWithoutRejectedValue() {
        // given
        ErrorResponse response = ErrorResponse.of(
                "INVALID_PASSWORD",
                "비밀번호가 규칙을 충족하지 않습니다.",
                List.of(new ErrorResponse.FieldError("password", "비밀번호는 8자 이상 64자 이하여야 합니다.")));

        // when
        JsonNode fieldErrors = jsonMapper.readTree(jsonMapper.writeValueAsString(response))
                .get("error").get("fieldErrors");

        // then
        // rejectedValue 같은 입력값 키가 없어야 함
        assertThat(fieldErrors).hasSize(1);
        JsonNode fieldError = fieldErrors.get(0);
        assertThat(fieldError.propertyNames()).containsExactlyInAnyOrder("field", "reason");
        assertThat(fieldError.get("field").asString()).isEqualTo("password");
        assertThat(fieldError.get("reason").asString()).isEqualTo("비밀번호는 8자 이상 64자 이하여야 합니다.");
    }
}
