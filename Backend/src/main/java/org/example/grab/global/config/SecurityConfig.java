package org.example.grab.global.config;

import org.example.grab.global.security.handler.RestAccessDeniedHandler;
import org.example.grab.global.security.handler.RestAuthenticationEntryPoint;
import org.example.grab.global.security.jwt.AccessTokenProperties;
import org.example.grab.global.security.jwt.JwtAuthenticationFilter;
import org.example.grab.global.security.jwt.JwtProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableConfigurationProperties(AccessTokenProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider,
                                            RestAuthenticationEntryPoint authenticationEntryPoint,
                                            RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        return http
                // 인증 근거는 access_token 쿠키의 JWT 하나다. 세션에 SecurityContext를 저장하지 않는다(M00-10)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // TODO(GR-44): CookieCsrfTokenRepository로 재활성화. 임시 해제
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/prometheus")
                        .permitAll()
                        // ponytail: 로컬 확인용 전 환경 개방. 운영 노출 정책은 GR-59(한재훈)에서 프로필로 제한
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .permitAll()
                        // 공개 카탈로그(목록·상세)만 GET으로 개방한다. 상세는 한 단계 경로(*)만 열어
                        // /api/v1/drops/{id}/wish(WISH API)와 판매자 경로는 계속 인증이 필요하다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories", "/api/v1/drops", "/api/v1/drops/*")
                        .permitAll()
                        // 판매자 API는 URL에서 SELLER를 먼저 거르고, 승인 상태는 CurrentSellerIdProvider가 DB로 다시 확인한다.
                        // 세그먼트 단위 매칭이라 /api/v1/seller-applications(USER의 판매자 신청)에는 걸리지 않는다.
                        .requestMatchers("/api/v1/seller/**").hasRole("SELLER")
                        // 지금은 판매자 이미지 업로드만 있다. 회원 프로필 업로드가 생기면 이 규칙을 /api/v1/uploads/images/**로 좁히거나 경로를 나눈다.
                        .requestMatchers("/api/v1/uploads/**").hasRole("SELLER")
                        .anyRequest().authenticated()
                )
                // 인증되지 않은 사용자가 보호된 api에 접근하면 authenticationEntryPoint가,
                // 인증됐지만 인가 규칙에 막히면 accessDeniedHandler가 공통 오류 형식으로 응답 생성
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                // 필터는 빈으로 등록하지 않고 여기서 만든다. 빈이면 서블릿 필터로도 등록돼 요청마다 두 번 실행된다
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider, authenticationEntryPoint),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
