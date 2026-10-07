package org.example.grab.domain.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationPropertiesTest {

    // 디코딩하면 40바이트. 테스트 전용 값이다
    private static final String SECRET = base64("grab-test-only-email-verification-secret");

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("올바른 키면 디코딩한 바이트를 돌려주고, 받은 배열을 바꿔도 설정은 바뀌지 않는다")
    void returnsDecodedCopyOfSecret() {
        // given
        EmailVerificationProperties properties = new EmailVerificationProperties(SECRET);
        byte[] first = properties.hmacSecretBytes();

        // when
        first[0] = 0;

        // then
        assertThat(new String(properties.hmacSecretBytes(), StandardCharsets.UTF_8))
                .isEqualTo("grab-test-only-email-verification-secret");
    }

    @Test
    @DisplayName("디코딩 후 정확히 32바이트면 허용한다")
    void accepts32Bytes() {
        // given
        String secret = Base64.getEncoder().encodeToString(new byte[32]);

        // when & then
        assertThat(new EmailVerificationProperties(secret).hmacSecretBytes()).hasSize(32);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("키가 없으면 거부한다")
    void rejectsMissingSecret(String secret) {
        assertThatThrownBy(() -> new EmailVerificationProperties(secret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EMAIL_VERIFICATION_SECRET")
                .hasMessageContaining("비어 있을 수 없습니다");
    }

    @Test
    @DisplayName("Base64가 아니면 거부하고, 메시지와 cause에 키 값이 없다")
    void rejectsNonBase64Secret() {
        assertThatThrownBy(() -> new EmailVerificationProperties("not-base64!@#$"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base64 형식")
                .hasMessageNotContaining("not-base64")
                .hasNoCause();
    }

    @Test
    @DisplayName("디코딩 후 32바이트 미만이면 길이만 알리고 거부한다")
    void rejectsShortSecret() {
        // given
        String secret = Base64.getEncoder().encodeToString(new byte[31]);

        // when & then
        assertThatThrownBy(() -> new EmailVerificationProperties(secret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32바이트 이상")
                .hasMessageContaining("현재 31바이트")
                .hasMessageNotContaining(secret);
    }

    @Test
    @DisplayName("toString에 키가 나오지 않는다")
    void masksSecretInToString() {
        // when
        String text = new EmailVerificationProperties(SECRET).toString();

        // then
        assertThat(text).isEqualTo("EmailVerificationProperties[hmacSecret=masked]").doesNotContain(SECRET);
    }
}
