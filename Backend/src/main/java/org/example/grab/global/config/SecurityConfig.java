package org.example.grab.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        // ponytail: 로컬 확인용 전 환경 개방. 운영 노출 정책은 GR-59(한재훈)에서 프로필로 제한
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // 공개 카탈로그(목록·상세)만 GET으로 개방한다. 상세는 한 단계 경로(*)만 열어
                        // /api/v1/drops/{id}/wish(WISH API)와 판매자 경로는 계속 인증이 필요하다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories", "/api/v1/drops", "/api/v1/drops/*")
                        .permitAll()
                        .anyRequest().authenticated())
                .formLogin(withDefaults())
                .httpBasic(withDefaults())
                .build();
    }
}
