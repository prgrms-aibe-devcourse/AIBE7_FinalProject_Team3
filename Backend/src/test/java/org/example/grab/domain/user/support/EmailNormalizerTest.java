package org.example.grab.domain.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

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

    @Test
    @DisplayName("이메일 전체를 소문자로 변환한다")
    void convertsToLowerCase() {
        // when
        String normalized = EmailNormalizer.normalize("User@Example.COM");

        // then
        assertThat(normalized).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("기본 로케일이 터키어여도 I를 점 없는 ı가 아닌 i로 변환한다")
    // 로케일을 지정하지 않은 toLowerCase()는 터키어 환경에서 I를 ı(U+0131)로 바꾸므로 Locale.ROOT를 쓰는지 확인하는 테스트
    void convertsToLowerCaseRegardlessOfDefaultLocale() {
        // given
        Locale originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr"));

        try {
            // when
            String normalized = EmailNormalizer.normalize("ADMIN@EXAMPLE.COM");

            // then
            assertThat(normalized).isEqualTo("admin@example.com");
        } finally {
            // 기본 로케일은 JVM 전역 설정이므로 다른 테스트에 영향이 없도록 되돌린다
            Locale.setDefault(originalLocale);
        }
    }
}
