package org.example.grab.global.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.example.grab.global.security.password.ConcurrencyLimitedPasswordEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
    비밀번호 해시 동시 실행 상한의 관측 지표(GR-178 M00-05). 거부 응답은 GlobalExceptionHandler가 WARN으로도 남기지만,
    폭주 중 거부 규모와 대기 추이는 로그 줄 수보다 지표로 보는 편이 쉽다. 등록 방식은 StockStreamMetricsConfig를 따른다.
 */
@Configuration
public class PasswordHashMetricsConfig {

    @Bean
    public MeterBinder passwordHashMetrics(ConcurrencyLimitedPasswordEncoder passwordEncoder) {
        return registry -> {
            FunctionCounter.builder("grab.password.hash.rejected", passwordEncoder,
                    ConcurrencyLimitedPasswordEncoder::rejectedCount).register(registry);
            Gauge.builder("grab.password.hash.waiting", passwordEncoder,
                    ConcurrencyLimitedPasswordEncoder::waitingCount).register(registry);
        };
    }
}
