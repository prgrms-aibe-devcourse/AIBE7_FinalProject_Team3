package org.example.grab.domain.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EmailVerificationCodeGeneratorTest {

    private final EmailVerificationCodeGenerator generator = new EmailVerificationCodeGenerator();

    @Test
    @DisplayName("코드는 항상 ASCII 숫자 6자리이고, 앞자리 0도 6자리로 유지되며 첫 자리에 0~9가 모두 나온다")
    void generatesSixDigitCodesIncludingLeadingZeros() {
        // given
        Set<Character> firstDigits = new HashSet<>();

        // when
        for (int i = 0; i < 10_000; i++) {
            String code = generator.generate();

            // then: 확인 요청 DTO의 형식([0-9]{6})과 같다
            assertThat(code).matches("[0-9]{6}");
            firstDigits.add(code.charAt(0));
        }

        // then: 첫 자리별 확률이 10%라 1만 번 중 한 숫자가 한 번도 안 나올 확률은 무시할 수 있다
        assertThat(firstDigits).containsExactlyInAnyOrder('0', '1', '2', '3', '4', '5', '6', '7', '8', '9');
    }
}
