package org.example.grab.domain.order.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class OrderNumberGeneratorTest {

    @Test
    @DisplayName("주문번호는 한국 날짜와 임의 식별자를 조합해 생성한다")
    void generateOrderNumber() {
        // given
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-21T15:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );
        OrderNumberGenerator generator = new OrderNumberGenerator(clock);

        // when
        String first = generator.generate();
        String second = generator.generate();

        // then
        assertThat(first).matches("ORD-20260922-[0-9A-F]{16}");
        assertThat(second).matches("ORD-20260922-[0-9A-F]{16}");
        assertThat(first).isNotEqualTo(second);
    }
}
