package org.example.grab.domain.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationHasherTest {

    private static final String SECRET_TEXT = "grab-test-only-email-verification-secret";
    private static final String OTHER_SECRET_TEXT = "grab-test-only-other-verification-secret";

    private final EmailVerificationHasher hasher = hasherWith(SECRET_TEXT);

    private static EmailVerificationHasher hasherWith(String secretText) {
        String secret = Base64.getEncoder().encodeToString(secretText.getBytes(StandardCharsets.UTF_8));
        return new EmailVerificationHasher(new EmailVerificationProperties(secret));
    }

    private static String hmacHex(String secretText, String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretText.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("이메일과 코드는 용도를 붙인 입력의 HMAC-SHA256 소문자 hex 64자다")
    void hashesWithPurposePrefix() throws Exception {
        // when
        String emailHash = hasher.hashEmail("user@example.com");
        String codeHash = hasher.hashVerificationCode("123456");

        // then
        assertThat(emailHash).hasSize(64).matches("[0-9a-f]+")
                .isEqualTo(hmacHex(SECRET_TEXT, "email:user@example.com"));
        assertThat(codeHash).hasSize(64).matches("[0-9a-f]+")
                .isEqualTo(hmacHex(SECRET_TEXT, "code:123456"));
    }

    @Test
    @DisplayName("IP는 용도를 붙인 입력의 HMAC-SHA256 소문자 hex 64자다")
    void hashesIpAddressWithPurposePrefix() throws Exception {
        // when
        String ipHash = hasher.hashIpAddress("203.0.113.7");

        // then
        assertThat(ipHash).hasSize(64).matches("[0-9a-f]+")
                .isEqualTo(hmacHex(SECRET_TEXT, "ip:203.0.113.7"));
    }

    @Test
    @DisplayName("같은 값이어도 이메일·코드·IP 해시는 서로 다르다")
    void separatesHashesByPurpose() {
        assertThat(hasher.hashEmail("123456")).isNotEqualTo(hasher.hashVerificationCode("123456"));
        assertThat(hasher.hashIpAddress("123456"))
                .isNotEqualTo(hasher.hashEmail("123456"))
                .isNotEqualTo(hasher.hashVerificationCode("123456"));
    }

    @Test
    @DisplayName("같은 입력은 같은 해시이고, 키가 다르면 다른 해시다")
    void dependsOnSecret() {
        // when
        String hash = hasher.hashVerificationCode("123456");

        // then
        assertThat(hasher.hashVerificationCode("123456")).isEqualTo(hash);
        assertThat(hasherWith(OTHER_SECRET_TEXT).hashVerificationCode("123456")).isNotEqualTo(hash);
    }

    @Test
    @DisplayName("코드 비교는 같은 코드면 true, 다른 코드·저장값 없음이면 false다")
    void matchesVerificationCode() {
        // given
        String stored = hasher.hashVerificationCode("123456");

        // when & then
        assertThat(hasher.matchesVerificationCode("123456", stored)).isTrue();
        assertThat(hasher.matchesVerificationCode("123457", stored)).isFalse();
        assertThat(hasher.matchesVerificationCode("123456", null)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("빈 입력은 해시하지 않는다")
    void rejectsBlankInput(String value) {
        assertThatThrownBy(() -> hasher.hashEmail(value)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hashVerificationCode(value)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hashIpAddress(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
