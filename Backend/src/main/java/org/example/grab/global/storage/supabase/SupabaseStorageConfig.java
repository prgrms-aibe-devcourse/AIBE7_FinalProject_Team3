package org.example.grab.global.storage.supabase;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(SupabaseStorageProperties.class)
public class SupabaseStorageConfig {

    // 발급 API가 응답하지 않아도 요청 스레드가 묶이지 않도록 연결·응답 타임아웃을 건다(TossPaymentsConfig와 같은 방식).
    @Bean
    public SupabaseStorageClient supabaseStorageClient(SupabaseStorageProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory);
        return new SupabaseStorageClient(builder, properties);
    }

    // 판매자 신청 서류(GR-91). 같은 프로젝트 URL·Secret Key를 쓰되 비공개 버킷(documentsBucket)에 올린다.
    @Bean
    public SupabaseDocumentStorageClient supabaseDocumentStorageClient(SupabaseStorageProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory);
        return new SupabaseDocumentStorageClient(builder, properties);
    }
}
