package org.example.grab.domain.order.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class OrderNumberGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final Clock clock;

    public OrderNumberGenerator() {
        this(Clock.systemUTC());
    }

    OrderNumberGenerator(Clock clock) {
        this.clock = clock;
    }

    public String generate() {
        String date = LocalDate.now(clock).format(DATE_FORMATTER);
        String uniqueSuffix = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return "ORD-" + date + "-" + uniqueSuffix;
    }
}
