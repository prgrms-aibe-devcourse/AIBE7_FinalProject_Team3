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
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

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
                // 모든 상태 변경 요청(POST·PUT·PATCH·DELETE)에 예외 없이 CSRF를 적용한다
                // spa()는 XSRF-TOKEN 쿠키를 JavaScript가 읽을 수 있게 하고, 쿠키 값을 그대로 담은 X-XSRF-TOKEN 헤더를 받는다.
                // spa()의 저장소에는 Secure·SameSite가 없어 명세 속성을 붙인 저장소로 바꾼다. spa()가 저장소를 덮어쓰므로 순서를 지킨다(M00-01)
                .csrf(csrf -> csrf
                        .spa()
                        .csrfTokenRepository(csrfTokenRepository())
                )
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/prometheus")
                        .permitAll()
                        // ponytail: 로컬 확인용 전 환경 개방. 운영 노출 정책은 GR-59(한재훈)에서 프로필로 제한
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .permitAll()
                        // 공개 카탈로그(목록·상세·재고 재조회)만 GET으로 개방한다. 상세는 한 단계 경로(*)만 열어
                        // /api/v1/drops/{id}/wish(WISH API)와 판매자 경로는 계속 인증이 필요하다.
                        // 재고 재조회는 /stocks만 명시해 /api/v1/drops/** 전체를 열지 않는다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories", "/api/v1/drops", "/api/v1/drops/*",
                                "/api/v1/drops/*/stocks")
                        .permitAll()
                        // 이메일 인증 코드 요청·확인은 가입 전 단계라 로그인 없이 호출한다(MEMBER_AUTH 1.2.1, 1.2.2)
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/email-verification",
                                "/api/v1/auth/email-verification/confirm")
                        .permitAll()
                        // 회원가입 완료 요청도 로그인 없이 호출한다. 가입 자격은 email_signup_token 쿠키의 가입 컨텍스트로 확인한다(MEMBER_AUTH 1.2.3)
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup")
                        .permitAll()
                        // 판매자 API는 URL에서 SELLER를 먼저 거르고, 승인 상태는 CurrentSellerIdProvider가 DB로 다시 확인한다.
                        // 세그먼트 단위 매칭이라 /api/v1/seller-applications(USER의 판매자 신청)에는 걸리지 않는다.
                        .requestMatchers("/api/v1/seller/**").hasRole("SELLER")
                        // 내 WISH 목록은 정확한 경로의 GET에 USER 권한을 요구한다. 비로그인 401, USER 없는 계정 403.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me/wishes").hasRole("USER")
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

    /*
        XSRF-TOKEN 쿠키(MEMBER_AUTH 1.1). 인증 토큰이 아니라 헤더에 같은 값을 넣기 위해 JavaScript가 읽어야 하므로 HttpOnly를 붙이지 않는다.
        Secure는 항상 붙인다. 브라우저는 http://localhost도 안전한 출처로 보므로 로컬 개발에서도 동작한다.
     */
    private static CookieCsrfTokenRepository csrfTokenRepository() {
        // js에서 요청 헤더에 X-XSRF-TOKEN을 입력하기 위해 쿠키 값을 읽어야 함
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
                .secure(true)
                .sameSite("Lax")
                .path("/"));
        return repository;
    }

    /*
        비밀번호는 Argon2id로 해시한다(NFR-001). 회원가입(GR-29)과 로그인(GR-34)이 함께 쓴다.
        값은 OWASP Password Storage Cheat Sheet의 최소 권장값(메모리 19 MiB, 반복 2, 병렬도 1)이다.
        Spring 기본값(defaultsForSpringSecurity_v5_8)은 메모리가 16 MiB라 쓰지 않는다.
        검증은 저장된 해시에 적힌 파라미터로 하므로, 값을 올려도 기존 해시는 그대로 검증된다.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    }
}
