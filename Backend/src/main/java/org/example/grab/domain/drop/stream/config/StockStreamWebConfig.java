package org.example.grab.domain.drop.stream.config;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.drop.stream.transport.StockStreamAsyncInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class StockStreamWebConfig implements WebMvcConfigurer {

    private final StockStreamAsyncInterceptor interceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/drops/stock-stream");
    }
}
