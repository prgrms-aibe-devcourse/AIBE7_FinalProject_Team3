package org.example.grab.domain.order.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class OrderNumberGenerator {

    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final Clock clock;

    public OrderNumberGenerator() {
        this(Clock.system(KOREA_ZONE));
    }

    OrderNumberGenerator(Clock clock) {
        this.clock = clock;
    }

    public String generate() {
        String date = LocalDate.now(clock).format(DATE_FORMAT);
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        return "ORD-" + date + "-" + randomPart;
    }
}
