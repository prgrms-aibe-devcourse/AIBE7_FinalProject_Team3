package org.example.grab.domain.auth.controller;

import jakarta.servlet.http.Cookie;
import org.example.grab.global.security.password.ConcurrencyLimitedPasswordEncoder;
import org.example.grab.global.security.password.PasswordHashProperties;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/*
    GR-178 M03-03: 비밀번호 해시 자리가 없을 때 로그인이 503 AUTH_TEMPORARILY_UNAVAILABLE + Retry-After로 거부되는지
    실제 SecurityFilterChain·GlobalExceptionHandler·PostgreSQL·Redis로 확인한다(MEMBER_AUTH 1.3).
    Argon2는 실제 파라미터 그대로 쓰되, 검증(matches) 도중에 멈출 수 있는 문을 앞에 두어 "해시가 진행 중인" 상태를 만든다.
    상한 1·짧은 대기 시간으로 띄우므로 다른 통합 테스트와 컨텍스트를 공유하지 않는다.
 */
@SpringBootTest(properties = {
        "grab.password-hash.max-concurrent=1",
        "grab.password-hash.acquire-timeout=100ms",
        "grab.password-hash.retry-after=3s"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, LoginHashLimitApiIntegrationTest.GatedPasswordEncoderConfig.class})
@Testcontainers
class LoginHashLimitApiIntegrationTest {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String CSRF_PATH = "/api/v1/auth/csrf";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";
    private static final String PASSWORD = "Password123!";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 컨테이너 Redis는 인증이 없다. 빈 비밀번호는 "비밀번호 없음"으로 처리된다
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ConcurrencyLimitedPasswordEncoder limitedPasswordEncoder;
    @Autowired
    private GatedPasswordEncoder gate;

    private final String run = UUID.randomUUID().toString().substring(0, 6);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void cleanUp() {
        // 실패한 테스트가 문을 닫은 채 끝나도 요청 스레드가 남지 않게 한다
        gate.open();
        executor.shutdownNow();
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", "login-hash-" + run + "-%");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private String email(String name) {
        return "login-hash-" + run + "-" + name + "@example.com";
    }

    private void insertUser(String name) {
        jdbcTemplate.update("INSERT INTO users (email, password_hash, nickname, status) VALUES (?, ?, ?, 'ACTIVE')",
                email(name), passwordEncoder.encode(PASSWORD), "hash-" + run + "-" + name);
    }

    private MockHttpServletResponse login(String email) throws Exception {
        Cookie csrf = mockMvc.perform(get(CSRF_PATH)).andReturn().getResponse().getCookie(XSRF_COOKIE);
        assertThat(csrf).isNotNull();
        return mockMvc.perform(post(LOGIN_PATH)
                        .cookie(new Cookie(XSRF_COOKIE, csrf.getValue()))
                        .header(XSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse();
    }

    private Long redisKeyCount() {
        return redisTemplate.execute((RedisCallback<Long>) connection -> connection.serverCommands().dbSize());
    }

    // 첫 로그인이 비밀번호 검증 도중에 멈춰 유일한 자리를 잡고 있게 만든다
    private Future<MockHttpServletResponse> holdPermitWithLogin(String email) throws Exception {
        gate.close();
        Future<MockHttpServletResponse> holder = executor.submit(() -> login(email));
        gate.awaitBlocked();
        return holder;
    }

    private static void assertRejected(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
        assertThat(response.getContentAsString())
                .contains("\"success\":false")
                .contains("\"code\":\"AUTH_TEMPORARILY_UNAVAILABLE\"");
        // 거부 응답에는 인증 쿠키를 발급하지 않는다(MEMBER_AUTH 1.3)
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .noneMatch(value -> value.startsWith("access_token=") || value.startsWith("refresh_token="));
    }

    @Test
    @DisplayName("비밀번호 해시 자리가 없으면 로그인을 503·Retry-After로 거부하고 쿠키·Refresh Token을 남기지 않는다")
    void rejectsLoginWhenNoHashPermit() throws Exception {
        // given: 회원 둘. 첫 회원의 로그인이 유일한 자리를 잡고 있다
        insertUser("holder");
        insertUser("waiter");
        Future<MockHttpServletResponse> holder = holdPermitWithLogin(email("holder"));
        long rejectedBefore = limitedPasswordEncoder.rejectedCount();

        // when
        MockHttpServletResponse rejected = login(email("waiter"));

        // then
        assertRejected(rejected);
        assertThat(limitedPasswordEncoder.rejectedCount()).isEqualTo(rejectedBefore + 1);
        assertThat(redisKeyCount()).isZero();

        // when: 자리를 쓰던 로그인이 끝나면
        gate.open();

        // then: 그 로그인은 정상으로 끝나고, Redis에는 그 세션(키 2개)만 남는다
        assertThat(holder.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        assertThat(redisKeyCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("없는 계정의 로그인도 같은 503으로 거부해 계정 존재 여부를 드러내지 않는다")
    void rejectsUnknownAccountTheSameWay() throws Exception {
        // given
        insertUser("holder");
        Future<MockHttpServletResponse> holder = holdPermitWithLogin(email("holder"));

        // when: 없는 계정은 더미 해시로 검증하므로 같은 자리를 기다린다
        MockHttpServletResponse rejected = login(email("nobody"));

        // then
        assertRejected(rejected);
        gate.open();
        assertThat(holder.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    }

    // 실제 Argon2 앞에 문을 둔 인코더. 문이 닫혀 있으면 matches가 멈춘다. encode(회원 생성·더미 해시)는 멈추지 않는다
    static final class GatedPasswordEncoder implements PasswordEncoder {

        private final PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
        private volatile CountDownLatch gate = new CountDownLatch(0);
        private final Semaphore blocked = new Semaphore(0);

        void close() {
            blocked.drainPermits();
            gate = new CountDownLatch(1);
        }

        void open() {
            gate.countDown();
        }

        void awaitBlocked() throws InterruptedException {
            assertThat(blocked.tryAcquire(10, TimeUnit.SECONDS)).as("첫 로그인이 비밀번호 검증에서 멈춰야 한다").isTrue();
        }

        @Override
        public String encode(CharSequence rawPassword) {
            return argon2.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            CountDownLatch current = gate;
            if (current.getCount() > 0) {
                blocked.release();
                try {
                    if (!current.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("문이 열리지 않았다");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return argon2.matches(rawPassword, encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return argon2.upgradeEncoding(encodedPassword);
        }
    }

    /*
        SecurityConfig의 인코더와 같은 데코레이터로 GatedPasswordEncoder를 감싸 우선 빈으로 둔다.
        서비스와 지표 등록(PasswordHashMetricsConfig)은 이 빈을 받는다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class GatedPasswordEncoderConfig {

        @Bean
        GatedPasswordEncoder gatedDelegate() {
            return new GatedPasswordEncoder();
        }

        @Bean
        @Primary
        ConcurrencyLimitedPasswordEncoder gatedPasswordEncoder(GatedPasswordEncoder gatedDelegate, PasswordHashProperties properties) {
            return new ConcurrencyLimitedPasswordEncoder(gatedDelegate, properties);
        }
    }
}
