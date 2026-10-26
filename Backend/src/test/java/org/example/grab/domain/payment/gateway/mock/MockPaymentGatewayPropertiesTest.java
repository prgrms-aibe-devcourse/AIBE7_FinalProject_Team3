package org.example.grab.domain.payment.gateway.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MockPaymentGatewayPropertiesTest {

    @Test
    @DisplayName("기본값(전부 0, 지연 20초)은 그대로 생성된다")
    void defaultsAreValid() {
        // given & when & then
        assertThatCode(() -> new MockPaymentGatewayProperties(0, 0, 0, 20))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("비율의 합이 1 이하면 각각 1이어도(합 1) 생성된다")
    void ratesSummingExactlyToOneAreValid() {
        // given & when & then
        assertThatCode(() -> new MockPaymentGatewayProperties(0.4, 0.6, 1, 0))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1})
    @DisplayName("confirmFailureRate가 0~1 범위를 벗어나면 거부한다")
    void rejectsOutOfRangeConfirmFailureRate(double invalidRate) {
        // given: invalidRate 파라미터
        // when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MockPaymentGatewayProperties(invalidRate, 0, 0, 20));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1})
    @DisplayName("confirmUnknownRate가 0~1 범위를 벗어나면 거부한다")
    void rejectsOutOfRangeConfirmUnknownRate(double invalidRate) {
        // given: invalidRate 파라미터
        // when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MockPaymentGatewayProperties(0, invalidRate, 0, 20));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1})
    @DisplayName("cancelUnknownRate가 0~1 범위를 벗어나면 거부한다")
    void rejectsOutOfRangeCancelUnknownRate(double invalidRate) {
        // given: invalidRate 파라미터
        // when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MockPaymentGatewayProperties(0, 0, invalidRate, 20));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    @DisplayName("confirmFailureRate가 NaN·무한대면 거부한다")
    void rejectsNonFiniteConfirmFailureRate(double invalidRate) {
        // given: invalidRate 파라미터
        // when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MockPaymentGatewayProperties(invalidRate, 0, 0, 20));
    }

    @Test
    @DisplayName("confirmFailureRate + confirmUnknownRate가 1을 넘으면 거부한다")
    void rejectsConfirmRatesSummingOverOne() {
        // given & when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MockPaymentGatewayProperties(0.6, 0.6, 0, 20));
    }

    @Test
    @DisplayName("resolveDelaySeconds가 음수면 거부한다")
    void rejectsNegativeResolveDelay() {
        // given & when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MockPaymentGatewayProperties(0, 0, 0, -1));
    }
}
