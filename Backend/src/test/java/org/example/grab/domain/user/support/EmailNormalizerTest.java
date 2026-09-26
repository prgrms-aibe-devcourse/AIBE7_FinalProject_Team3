package org.example.grab.domain.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class EmailNormalizerTest {

    @Test
    @DisplayName("null을 정규화하면 예외 없이 null을 반환한다")
    // null을 필수값 검증에서 VALIDATION_FAILED로 처리할 수 있도록 정규화 단계에서 막지 않는지 확인하는 테스트
    void returnsNullForNullEmail() {
        // when & then
        assertThatCode(() -> EmailNormalizer.normalize(null)).doesNotThrowAnyException();
        assertThat(EmailNormalizer.normalize(null)).isNull();
    }

    @ParameterizedTest
    // 소문자 변환의 영향을 받지 않도록 이미 소문자인 주소에 앞뒤 공백만 붙인다
    @ValueSource(strings = {
            "  user@example.com  ",
            "\tuser@example.com\t",
            "\nuser@example.com\r\n",
            "\u3000user@example.com\u3000"
    })
    @DisplayName("이메일 앞뒤의 공백 문자를 제거한다")
    // 전각 스페이스(U+3000)는 trim()으로 제거되지 않으므로 strip()을 사용하는지 확인하는 테스트
    void stripsLeadingAndTrailingWhitespace(String email) {
        // when
        String normalized = EmailNormalizer.normalize(email);

        // then
        assertThat(normalized).isEqualTo("user@example.com");
    }
}
