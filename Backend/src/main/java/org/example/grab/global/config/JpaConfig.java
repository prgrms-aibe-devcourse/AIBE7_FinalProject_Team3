package org.example.grab.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    /*
        PostgreSQL timestamptz는 마이크로초까지만 저장한다. 나노초를 그대로 두면 저장 직후 응답(메모리 값)과
        이후 조회 응답(DB 값)의 createdAt·updatedAt이 달라지므로 저장 전에 마이크로초로 자른다(GR-29 M04-01).
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
    }
}
