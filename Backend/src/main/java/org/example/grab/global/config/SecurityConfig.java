package org.example.grab.global.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.example.grab.global.security.handler.RestAccessDeniedHandler;
import org.example.grab.global.security.handler.RestAuthenticationEntryPoint;
import org.example.grab.global.security.jwt.AccessTokenProperties;
import org.example.grab.global.security.jwt.JwtAuthenticationFilter;
import org.example.grab.global.security.jwt.JwtProvider;
import org.example.grab.global.security.password.ConcurrencyLimitedPasswordEncoder;
import org.example.grab.global.security.password.PasswordHashProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableConfigurationProperties({AccessTokenProperties.class, CorsProperties.class, PasswordHashProperties.class})
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider, RestAuthenticationEntryPoint authenticationEntryPoint, RestAccessDeniedHandler accessDeniedHandler,
                                            // 소셜 로그인 설정은 auth 도메인(OAuth2LoginConfig)이 만든다. global은 도메인을 참조하지 않으므로 Customizer로 받는다(GR-46)
                                            Customizer<OAuth2LoginConfigurer<HttpSecurity>> oauth2LoginCustomizer) throws Exception {
        return http
                // 인증 근거는 access_token 쿠키의 JWT 하나다. 세션에 SecurityContext를 저장하지 않는다(M00-10)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // 아래 corsConfigurationSource 빈을 쓴다. 허용하지 않은 Origin의 요청은 인증·CSRF보다 먼저 403으로 거부된다
                .cors(Customizer.withDefaults())
                // 모든 상태 변경 요청(POST·PUT·PATCH·DELETE)에 예외 없이 CSRF를 적용한다
                // spa()는 XSRF-TOKEN 쿠키를 JavaScript가 읽을 수 있게 하고, 쿠키 값을 그대로 담은 X-XSRF-TOKEN 헤더를 받는다.
                // spa()의 저장소에는 Secure·SameSite가 없어 명세 속성을 붙인 저장소로 바꾼다. spa()가 저장소를 덮어쓰므로 순서를 지킨다(M00-01)
                // JWT 필터는 요청마다 인증을 다시 세우고 STATELESS라 세션에 남지 않는다. 기본 전략은 이를 매번 새 로그인으로 보고
                // XSRF-TOKEN을 지우고 다시 발급해, 먼저 읽어 둔 토큰의 상태 변경 요청이 403이 된다. 로그인도 토큰을 교체하지 않으므로(MEMBER_AUTH 1.3) 끈다
                .csrf(csrf -> csrf
                        .spa()
                        .csrfTokenRepository(csrfTokenRepository())
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                )
                .authorizeHttpRequests(requests -> requests
                        // SSE의 오류 dispatch만 허용한다. 다른 보호 API의 ERROR/ASYNC를 일괄 공개하지 않는다.
                        .requestMatchers(request -> request.getDispatcherType() == DispatcherType.ERROR
                                && "GET".equals(request.getMethod())
                                && "/api/v1/drops/stock-stream".equals(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)))
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/prometheus")
                        .permitAll()
                        // ponytail: 로컬 확인용 전 환경 개방. 운영 노출 정책은 GR-59(한재훈)에서 프로필로 제한
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .permitAll()
                        // 공개 카탈로그(목록·상세·재고 재조회·관련 DROP 추천)만 GET으로 개방한다. 상세는 한 단계 경로(*)만 열어
                        // /api/v1/drops/{id}/wish(WISH API)와 판매자 경로는 계속 인증이 필요하다.
                        // 재고 재조회(/stocks)·관련 DROP 추천(/related)만 명시해 /api/v1/drops/** 전체를 열지 않는다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories", "/api/v1/drops", "/api/v1/drops/*",
                                "/api/v1/drops/*/stocks", "/api/v1/drops/*/related", "/api/v1/drops/stock-stream")
                        .permitAll()
                        // CSRF 토큰 발급은 로그인 전에도 필요하다(MEMBER_AUTH 1.1)
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf")
                        .permitAll()
                        // 이메일 인증 코드 요청·확인은 가입 전 단계라 로그인 없이 호출한다(MEMBER_AUTH 1.2.1, 1.2.2)
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/email-verification",
                                "/api/v1/auth/email-verification/confirm")
                        .permitAll()
                        // 회원가입 완료 요청도 로그인 없이 호출한다. 가입 자격은 email_signup_token 쿠키의 가입 컨텍스트로 확인한다(MEMBER_AUTH 1.2.3)
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup")
                        .permitAll()
                        // 로그인은 인증 전 단계라 로그인 없이 호출한다. CSRF 예외는 두지 않는다(MEMBER_AUTH 1.3, GR-44 M00-02)
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                        .permitAll()
                        // 재발급은 Access Token이 만료된 뒤 호출하므로 로그인 없이 연다. 자격은 refresh_token 쿠키로 확인하고,
                        // CSRF 예외는 두지 않는다(MEMBER_AUTH 1.7, GR-44 M00-02). JwtAuthenticationFilter는 /api/v1/auth/**를 건너뛴다
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh")
                        .permitAll()
                        // 로그아웃도 Access Token이 만료된 뒤 호출할 수 있어야 하므로 로그인 없이 연다. 폐기할 세션은 refresh_token 쿠키로 찾고,
                        // CSRF 예외는 두지 않는다(MEMBER_AUTH 1.8, GR-36 M00-01)
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout")
                        .permitAll()
                        // 소셜 로그인 시작·콜백은 로그인 전 단계라 연다. kakao·google 시작과 모든 콜백은 OAuth2 필터가 먼저 처리하고,
                        // 그 밖의 provider GET은 Controller가 400 UNSUPPORTED_OAUTH2_PROVIDER로 응답한다(MEMBER_AUTH 1.4·1.5, GR-46 M00-02)
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/oauth2/*", "/api/v1/auth/oauth2/*/callback")
                        .permitAll()
                        // 소셜 가입 완료는 oauth2_signup_token 쿠키의 가입 컨텍스트로 확인한다. CSRF 예외는 두지 않는다(MEMBER_AUTH 1.6)
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/oauth2/signup")
                        .permitAll()
                        // 판매자 API는 URL에서 SELLER를 먼저 거르고, 승인 상태는 CurrentSellerIdProvider가 DB로 다시 확인한다.
                        // 세그먼트 단위 매칭이라 /api/v1/seller-applications(USER의 판매자 신청)에는 걸리지 않는다.
                        .requestMatchers("/api/v1/seller/**").hasRole("SELLER")
                        // 운영자 전용 API. 판매자 신청 심사·서류 열람은 ADMIN만 접근한다(GR-92)
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 내 정보 조회도 정확한 경로의 GET에만 USER 권한을 요구한다. /users/me/** 전체에는 걸지 않는다.
                        // 정지·탈퇴 회원은 토큰이 유효해도 서비스가 401로 거부한다(MEMBER_AUTH 1.9, GR-97 M00-03·04)
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me").hasRole("USER")
                        // 내 닉네임 수정·비밀번호 변경도 정확한 경로의 PATCH에만 USER 권한을 요구한다. CSRF 예외는 두지 않는다.
                        // 정지·탈퇴 회원은 GET과 같이 서비스가 401로 거부한다(MEMBER_AUTH 1.10·1.11, GR-129 M00-06)
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/users/me", "/api/v1/users/me/password").hasRole("USER")
                        // 내 WISH 목록은 정확한 경로의 GET에 USER 권한을 요구한다. 비로그인 401, USER 없는 계정 403.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me/wishes").hasRole("USER")
                        // 내 프로필 이미지 업로드 URL 발급·등록·삭제도 정확한 경로·메서드에만 USER 권한을 요구한다. CSRF 예외는 두지 않는다.
                        // 정지·탈퇴 회원은 GET과 같이 서비스가 401로 거부한다(MEMBER_AUTH 1.12·1.13, GR-130 M00-07)
                        .requestMatchers(HttpMethod.POST, "/api/v1/users/me/profile-image/upload-url").hasRole("USER")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/users/me/profile-image").hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/users/me/profile-image").hasRole("USER")
                        // 판매자 DROP 이미지 업로드 전용이다. 회원 프로필 업로드는 위 /users/me/profile-image 경로로 나눠 이 규칙에 걸리지 않는다(GR-130 M00-01).
                        .requestMatchers("/api/v1/uploads/**").hasRole("SELLER")
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2LoginCustomizer)
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
        허용한 프론트엔드 Origin에서만 쿠키를 포함한 API 요청을 받는다(COMMON 1.3, GR-44 M00-04).
        - 헤더는 JSON 본문, CSRF 토큰, 주문 멱등성 키만 허용한다
        - Swagger·Actuator는 같은 출처에서만 쓰므로 /api/**에만 적용한다
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins()); // 설정에 명시된 프론트 출처만 허용
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE")); // 명시된 메서드만 허용
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key")); // 허용 헤더
        configuration.setAllowCredentials(true); // 쿠키를 포함한 다른 출처 요청 허용

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
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
        해시 1건이 힙 약 19 MiB를 잡으므로 동시 실행 수를 묶는 인코더로 감싼다(GR-178, NFR-014).
        반환 타입을 구체 클래스로 두어 지표 등록(PasswordHashMetricsConfig)이 거부·대기 수를 읽게 한다. 사용하는 쪽은 PasswordEncoder로 받는다.
     */
    @Bean
    ConcurrencyLimitedPasswordEncoder passwordEncoder(PasswordHashProperties passwordHashProperties) {
        return new ConcurrencyLimitedPasswordEncoder(
                new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2), passwordHashProperties);
    }
}
