package org.example.grab.domain.user.error;

import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserErrorCodeTest {

    @ParameterizedTest
    // | 를 기준으로 각각 errorCode와 message에 삽입되어 테스트 진행
    @CsvSource(delimiter = '|', value = {
            "INVALID_EMAIL    | 이메일 형식이 올바르지 않습니다.",
            "INVALID_PASSWORD | 비밀번호가 규칙을 충족하지 않습니다.",
            "INVALID_NICKNAME | 닉네임이 규칙을 충족하지 않습니다."
    })
    @DisplayName("회원가입 입력 오류 코드는 상수 이름을 코드로, HTTP 400과 고정 메시지를 가진다")
    // UserErrorCode의 각 상수가 API 명세에 정한 대로 정의되어 있는지 확인하는 테스트
    void definesSignupInputErrorCodes(UserErrorCode errorCode, String message) {
        // then
        assertThat(errorCode.getCode()).isEqualTo(errorCode.name());
        assertThat(errorCode.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode.getMessage()).isEqualTo(message);
    }

    @Test
    @DisplayName("공통 오류 코드와 같은 코드 이름을 다시 정의하지 않는다")
    // UserErrorCode의 코드 이름이 CommonErrorCode의 코드 이름과 하나도 겹치지 않는지 검증하는 테스트
    // 설계상 enum이 달라도 같은 이름을 다시 정의하면 안됨
    void doesNotRedefineCommonErrorCodes() {
        // given
        // CommonErrorCode의 모든 enum value를 뽑고 getCode를 통해 name()만 List에 저장
        List<String> commonCodes = Arrays.stream(CommonErrorCode.values())
                .map(CommonErrorCode::getCode)
                .toList();

        // then
        // UserErrorCode의 모든 code가 CommonCodes 리스트의 값들과 하나도 일치하지 않는지 판별
        assertThat(UserErrorCode.values())
                .extracting(UserErrorCode::getCode)
                .doesNotContainAnyElementsOf(commonCodes);
    }
}
