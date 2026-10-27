package org.example.grab.domain.payment.gateway.mock;

import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// grab.payment.mock-gateway.enabled=true일 때만 활성화되고, TossPaymentsConfig의 빈은 그때 꺼진다(둘 다 PaymentGateway 단일 빈 자리를 다툰다).
@Configuration
@ConditionalOnProperty(prefix = "grab.payment.mock-gateway", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MockPaymentGatewayProperties.class)
public class MockPaymentGatewayConfig {

    @Bean
    public PaymentGateway paymentGateway(MockPaymentGatewayProperties properties) {
        return new MockPaymentGateway(properties);
    }
}
