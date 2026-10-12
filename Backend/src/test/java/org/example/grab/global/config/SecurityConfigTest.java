package org.example.grab.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/*
    GR-29 M01-02: SecurityConfig가 등록하는 비밀번호 인코더를 Spring 컨텍스트 없이 확인한다.
    빈 메서드를 직접 호출해 실제로 등록되는 파라미터를 검증한다.
 */
class SecurityConfigTest {

    private static final String PASSWORD = "Password123!";

    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

    @Test
    @DisplayName("해시는 Argon2id 형식이고 메모리 19 MiB·반복 2·병렬도 1이 적히며 원문을 포함하지 않는다")
    void encodesWithArgon2idParameters() {
        // when
        String hash = encoder.encode(PASSWORD);

        // then
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("같은 비밀번호만 일치한다")
    void matchesOnlySamePassword() {
        // given
        String hash = encoder.encode(PASSWORD);

        // when & then
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        assertThat(encoder.matches("Password123?", hash)).isFalse();
    }

    @Test
    @DisplayName("같은 비밀번호도 해시할 때마다 솔트가 달라 결과가 다르고, 둘 다 검증된다")
    void usesRandomSalt() {
        // when
        String first = encoder.encode(PASSWORD);
        String second = encoder.encode(PASSWORD);

        // then
        assertThat(first).isNotEqualTo(second);
        assertThat(encoder.matches(PASSWORD, first)).isTrue();
        assertThat(encoder.matches(PASSWORD, second)).isTrue();
    }

    @Test
    @DisplayName("최대 길이 64자 비밀번호도 자르지 않아, 마지막 한 글자만 달라도 일치하지 않는다")
    void doesNotTruncateLongPassword() {
        // given
        String password = "Aa1!".repeat(16);
        String lastCharChanged = password.substring(0, 63) + "?";
        String hash = encoder.encode(password);

        // when & then
        assertThat(password).hasSize(64);
        assertThat(encoder.matches(password, hash)).isTrue();
        assertThat(encoder.matches(lastCharChanged, hash)).isFalse();
    }

    @Test
    @DisplayName("다른 파라미터로 만든 해시도 해시에 적힌 값으로 검증되고, 더 약한 해시는 다시 해시 대상이다")
    void verifiesHashWithStoredParameters() {
        // given: Spring 기본값(메모리 16 MiB)으로 만든 해시. 파라미터를 올린 뒤에도 기존 해시가 검증되어야 한다
        String weakerHash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD);
        String currentHash = encoder.encode(PASSWORD);

        // when & then
        assertThat(weakerHash).startsWith("$argon2id$v=19$m=16384,t=2,p=1$");
        assertThat(encoder.matches(PASSWORD, weakerHash)).isTrue();
        assertThat(encoder.upgradeEncoding(weakerHash)).isTrue();
        assertThat(encoder.upgradeEncoding(currentHash)).isFalse();
    }
}
