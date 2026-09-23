package org.example.grab.domain.order.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrderNumberGeneratorTest {

    @Test
    @DisplayName("주문번호는 UTC 날짜와 UUID를 조합해 고유하게 생성한다")
    void generatesUniqueOrderNumber() {
        // given
        Clock clock = Clock.fixed(Instant.parse("2026-09-23T01:00:00Z"), ZoneOffset.UTC);
        OrderNumberGenerator generator = new OrderNumberGenerator(clock);
        Set<String> orderNumbers = new HashSet<>();

        // when
        for (int i = 0; i < 1_000; i++) {
            orderNumbers.add(generator.generate());
        }

        // then
        assertThat(orderNumbers).hasSize(1_000);
        assertThat(orderNumbers).allMatch(number -> number.matches("ORD-20260923-[0-9A-F]{32}"));
    }
}
