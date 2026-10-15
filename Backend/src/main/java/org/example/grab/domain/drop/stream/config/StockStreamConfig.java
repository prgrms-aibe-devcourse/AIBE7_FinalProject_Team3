package org.example.grab.domain.drop.stream.config;

import org.example.grab.domain.drop.stream.service.StockStreamRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(StockStreamProperties.class)
public class StockStreamConfig {

    @Bean
    public StockStreamRegistry stockStreamRegistry(StockStreamProperties properties) {
        return new StockStreamRegistry(properties, Clock.systemUTC());
    }
}
