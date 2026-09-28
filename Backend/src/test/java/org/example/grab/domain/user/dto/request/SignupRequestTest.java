package org.example.grab.domain.user.dto.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SignupRequestTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {
            "  드롭헌터  ",
            "\t드롭헌터\t",
            "\n드롭헌터\r\n",
            "　드롭헌터　"
    })
    @DisplayName("닉네임 앞뒤의 공백 문자를 제거한다")
    // 전각 스페이스(U+3000)는 trim()으로 제거되지 않으므로 strip()을 사용하는지 확인하는 테스트
    void stripsNicknameWhitespace(String nickname) {
        // when
        SignupRequest request = new SignupRequest("Password123!", nickname);

        // then
        assertThat(request.nickname()).isEqualTo("드롭헌터");
    }

    @Test
    @DisplayName("닉네임 중간의 공백은 삭제하지 않고 그대로 남긴다")
    // 중간 공백을 지우면 다른 닉네임으로 바뀌므로, 남겨 두고 이후 허용 문자 검증에서 INVALID_NICKNAME으로 거부하게 하는지 확인하는 테스트
    void keepsWhitespaceInsideNickname() {
        // when
        SignupRequest request = new SignupRequest("Password123!", " 드롭 헌터 ");

        // then
        assertThat(request.nickname()).isEqualTo("드롭 헌터");
    }

    @Test
    @DisplayName("닉네임이 null이면 예외 없이 null로 둔다")
    // null을 필수값 검증에서 VALIDATION_FAILED로 처리할 수 있도록 생성 단계에서 막지 않는지 확인하는 테스트
    void keepsNullNickname() {
        // when
        SignupRequest request = new SignupRequest("Password123!", null);

        // then
        assertThat(request.nickname()).isNull();
    }

    @Test
    @DisplayName("공백만 있는 닉네임은 빈 문자열이 된다")
    // 앞뒤 공백 제거 후 빈 값이 필수값 위반(VALIDATION_FAILED)으로 분류되도록 빈 문자열로 남는지 확인하는 테스트
    void stripsBlankNicknameToEmpty() {
        // when
        SignupRequest request = new SignupRequest("Password123!", " \t　 ");

        // then
        assertThat(request.nickname()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"  Password123!  ", "\tPassword123!", "Pass word123!"})
    @DisplayName("비밀번호는 공백을 포함해 전달받은 그대로 둔다")
    // 비밀번호를 trim하면 사용자가 입력한 값과 다른 값이 저장되므로 가공하지 않는지 확인하는 테스트
    void keepsOriginalPassword(String password) {
        // when
        SignupRequest request = new SignupRequest(password, "드롭헌터");

        // then
        assertThat(request.password()).isEqualTo(password);
    }

    @Test
    @DisplayName("JSON 바인딩에서도 닉네임 앞뒤 공백이 제거되고 비밀번호는 그대로 남는다")
    // 요청 본문 역직렬화가 정규 생성자를 거쳐, Bean Validation 전에 닉네임이 정리되는지 확인하는 테스트
    void stripsNicknameWhenDeserialized() {
        // given
        String body = """
                {"password": " Password123! ", "nickname": "\\u3000드롭헌터 "}
                """;

        // when
        SignupRequest request = jsonMapper.readValue(body, SignupRequest.class);

        // then
        assertThat(request.nickname()).isEqualTo("드롭헌터");
        assertThat(request.password()).isEqualTo(" Password123! ");
    }
}
