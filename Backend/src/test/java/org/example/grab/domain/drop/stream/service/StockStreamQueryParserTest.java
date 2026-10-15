package org.example.grab.domain.drop.stream.service;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.grab.domain.drop.stream.StockStreamTestSupport.defaults;

class StockStreamQueryParserTest {

    private final StockStreamQueryParser parser = new StockStreamQueryParser(defaults());

    @Test
    @DisplayName("선행 0·중복을 정규화하고 안전 정수 최댓값까지 오름차순으로 반환한다")
    void normalizesIds() {
        // when
        List<Long> result = parser.parse(List.of("0002,1,2,9007199254740991"));

        // then
        assertThat(result).containsExactly(1L, 2L, 9_007_199_254_740_991L);
        assertThatThrownBy(() -> result.add(3L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "000", "1,", ",1", "1,,2", "1, 2", " 1", "1 ", "+1", "-1",
            "1.0", "1e2", "１", "١", "9007199254740992", "999999999999999999999999", "1\n"})
    @DisplayName("빈 항목·비ASCII 숫자·부호·공백·범위 위반은 INVALID_REQUEST다")
    void rejectsMalformedIds(String value) {
        // when & then
        assertInvalid(List.of(value));
    }

    @Test
    @DisplayName("필수 파라미터 누락과 같은 이름의 파라미터 중복을 거부한다")
    void rejectsMissingOrRepeatedParameter() {
        // when & then
        assertInvalid(null);
        assertInvalid(List.of());
        assertInvalid(Collections.singletonList(null));
        assertInvalid(List.of("1", "2"));
    }

    @Test
    @DisplayName("중복 제거 전 100개는 허용하고 101개는 거부한다")
    void limitsRawTokenCount() {
        // when & then
        assertThat(parser.parse(List.of(String.join(",", Collections.nCopies(100, "1")))))
                .containsExactly(1L);
        assertInvalid(List.of(String.join(",", Collections.nCopies(101, "1"))));
    }

    @Test
    @DisplayName("긴 선행 0은 정규화하되 오버플로로 500 오류가 나지 않는다")
    void handlesLongLeadingZeros() {
        // when & then
        assertThat(parser.parse(List.of("0".repeat(1000) + "1"))).containsExactly(1L);
    }

    private void assertInvalid(List<String> values) {
        assertThatThrownBy(() -> parser.parse(values)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST));
    }
}
