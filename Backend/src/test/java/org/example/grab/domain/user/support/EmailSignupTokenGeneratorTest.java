package org.example.grab.domain.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// GR-61 M02-03: 가입 컨텍스트 토큰 원문은 43자 Base64URL이고, 생성할 때마다 다르다
class EmailSignupTokenGeneratorTest {

    private final EmailSignupTokenGenerator generator = new EmailSignupTokenGenerator();

    @Test
    @DisplayName("원문은 패딩 없는 Base64URL 문자로만 된 43자이고, 디코딩하면 32바이트다")
    void generatesBase64UrlTokenOf32Bytes() {
        // when
        String token = generator.generate();

        // then: 쿠키에서 문제가 되는 +, /, = 가 없다
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
    }

    @Test
    @DisplayName("연속으로 생성한 값이 서로 다르다")
    void generatesDistinctTokens() {
        // given
        Set<String> tokens = new HashSet<>();

        // when
        for (int i = 0; i < 1_000; i++) {
            tokens.add(generator.generate());
        }

        // then
        assertThat(tokens).hasSize(1_000);
    }
}
