package org.example.grab.domain.order.controller;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderIdParserTest {

    @Test
    @DisplayName("정규 표기 UUID는 대소문자와 관계없이 주문 ID로 변환한다")
    void parsesCanonicalUuid() {
        // given
        UUID orderId = UUID.fromString("b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d");

        // when
        UUID lower = OrderIdParser.parse("b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d");
        UUID upper = OrderIdParser.parse("B2D4F6A8-1C3E-4A5B-8C7D-9E0F1A2B3C4D");

        // then
        assertThat(lower).isEqualTo(orderId);
        assertThat(upper).isEqualTo(orderId);
    }

    @Test
    @DisplayName("UUID가 아닌 값은 RESOURCE_NOT_FOUND로 거부한다")
    void rejectsNonUuid() {
        // when & then
        assertThatThrownBy(() -> OrderIdParser.parse("not-a-uuid"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("자릿수가 모자란 축약 표기 UUID는 RESOURCE_NOT_FOUND로 거부한다")
    void rejectsAbbreviatedUuid() {
        // when & then
        assertThatThrownBy(() -> OrderIdParser.parse("1-2-3-4-5"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
}
