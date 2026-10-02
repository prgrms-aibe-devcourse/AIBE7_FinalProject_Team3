package org.example.grab.domain.auth.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// GR-33 U2·U3: 원문의 SHA-256 hex와 Redis 키 형식
class RefreshTokenHasherTest {

    @Test
    @DisplayName("알려진 입력의 SHA-256 hex가 기대값과 같다")
    void hashesWithSha256Hex() {
        // when
        String hash = RefreshTokenHasher.hash("abc");

        // then: FIPS 180-2 테스트 벡터
        assertThat(hash).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("해시는 소문자 hex 64자이고 같은 원문이면 같은 값이다")
    void hashesToLowercaseHexOf64Characters() {
        // given
        String rawToken = new RefreshTokenGenerator().generate();

        // when
        String hash = RefreshTokenHasher.hash(rawToken);

        // then
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
        assertThat(RefreshTokenHasher.hash(rawToken)).isEqualTo(hash);
    }

    @Test
    @DisplayName("키는 auth:refresh-token: 뒤에 해시를 붙인 값이고 원문을 포함하지 않는다")
    void buildsKeyFromHashWithoutRawToken() {
        // given
        String rawToken = new RefreshTokenGenerator().generate();

        // when
        String key = RefreshTokenHasher.toKey(rawToken);

        // then
        assertThat(key).isEqualTo("auth:refresh-token:" + RefreshTokenHasher.hash(rawToken));
        assertThat(key).doesNotContain(rawToken);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    @DisplayName("원문이 없거나 비어 있으면 예외가 발생한다")
    void rejectsMissingRawToken(String rawToken) {
        // when & then
        assertThatThrownBy(() -> RefreshTokenHasher.hash(rawToken))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
