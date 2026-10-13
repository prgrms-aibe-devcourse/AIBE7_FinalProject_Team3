package org.example.grab.domain.seller.gateway.nts;

import org.example.grab.domain.seller.gateway.BusinessRegistrationVerifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(NtsBusinessRegistrationProperties.class)
public class NtsBusinessRegistrationConfig {

    // 상태조회 API가 응답하지 않아도 요청 스레드가 묶이지 않도록 연결·응답 타임아웃을 건다(TossPaymentsConfig와 같은 방식).
    @Bean
    public BusinessRegistrationVerifier businessRegistrationVerifier(NtsBusinessRegistrationProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory);
        return new NtsBusinessRegistrationVerifier(builder, properties.serviceKey());
    }
}
