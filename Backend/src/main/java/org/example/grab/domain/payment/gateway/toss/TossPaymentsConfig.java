package org.example.grab.domain.payment.gateway.toss;

import org.example.grab.domain.payment.gateway.PaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(TossPaymentsProperties.class)
public class TossPaymentsConfig {

    // 승인 API가 응답하지 않아도 요청 스레드가 무한정 묶이지 않도록 연결·응답 타임아웃을 건다.
    // grab.payment.mock-gateway.enabled=true면 MockPaymentGatewayConfig가 대신 PaymentGateway 빈을 등록한다(GR-154/155 부하테스트).
    @Bean
    @ConditionalOnProperty(prefix = "grab.payment.mock-gateway", name = "enabled", havingValue = "false", matchIfMissing = true)
    public PaymentGateway tossPaymentGateway(TossPaymentsProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory);
        return new TossPaymentGateway(builder, properties.secretKey());
    }
}
