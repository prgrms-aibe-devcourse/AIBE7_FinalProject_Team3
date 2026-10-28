package org.example.grab.domain.order.config;

import org.example.grab.domain.order.service.OrderCreationLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OrderCreationLimitProperties.class)
public class OrderCreationLimiterConfig {

    @Bean
    OrderCreationLimiter orderCreationLimiter(OrderCreationLimitProperties properties) {
        return new OrderCreationLimiter(properties);
    }
}
