package org.example.grab.domain.user.service;

import org.example.grab.domain.user.dto.request.SignupRequest;
import org.example.grab.domain.user.dto.response.SignupResponse;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.EmailSignupContextRepository;
import org.example.grab.domain.user.support.EmailSignupTokenGenerator;
import org.example.grab.global.error.BusinessException;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
    GR-29 M04: 실제 PostgreSQL(공통 컨테이너)·Redis(Testcontainers)·Argon2 인코더로 가입 서비스를 확인한다.
    @SpringBootTest는 롤백하지 않으므로 테스트마다 고유한 이메일·닉네임을 쓰고, 만든 회원을 직접 지운다.
    요청 값 오류(VALIDATION_FAILED 등)는 서비스 호출 전에 끝나 컨텍스트에 닿지 않으므로 GR-30에서 확인한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class SignupServiceIntegrationTest {

    private static final String PASSWORD = "Password123!";
    private static final int CONCURRENT_REQUESTS = 8;

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
    private SignupService signupService;

    @Autowired
    private EmailSignupContextRepository signupContextRepository;

    @Autowired
    private EmailSignupTokenGenerator tokenGenerator;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // 테스트가 쓴 이메일·닉네임의 공통 접두어. 다른 테스트의 데이터와 섞이지 않게 한다
    private final String run = UUID.randomUUID().toString().substring(0, 6);

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", "signup-" + run + "-%");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private String email(String name) {
        return "signup-" + run + "-" + name + "@example.com";
    }

    // 닉네임 규칙(2~10자)에 맞춘다. 대소문자 비교를 위해 영문을 섞는다
    private String nickname(String prefix) {
        return prefix + run;
    }

    // 인증 코드 확인(GR-61)을 마친 상태처럼 컨텍스트를 만든다
    private String issueContext(String email) {
        String token = tokenGenerator.generate();
        signupContextRepository.save(token, email);
        return token;
    }

    private int countUsersByEmail(String email) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
    }

    private int countUsersByNickname(String nickname) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE lower(nickname) = lower(?)", Integer.class, nickname);
    }

    private static void assertErrorCode(Callable<?> call, UserErrorCode errorCode) {
        assertThatThrownBy(call::call)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    // 모든 요청을 동시에 시작하고, 성공 응답 또는 던진 예외를 모은다
    private static List<Object> runConcurrently(List<Callable<SignupResponse>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<SignupResponse>> futures = new ArrayList<>();
            for (Callable<SignupResponse> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<SignupResponse> future : futures) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    results.add(e.getCause());
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<UserErrorCode> errorCodes(List<Object> results) {
        return results.stream()
                .filter(BusinessException.class::isInstance)
                .map(result -> (UserErrorCode) ((BusinessException) result).getErrorCode())
                .toList();
    }

    @Test
    @DisplayName("M04-01·04: 컨텍스트의 이메일로 회원이 저장되고 Argon2id 해시로 검증되며, 원문 비밀번호는 응답·DB·로그에 없고 같은 컨텍스트로 다시 가입할 수 없다")
    void signsUpAndConsumesContext(CapturedOutput output) {
        // given
        String email = email("ok");
        String token = issueContext(email);

        // when
        SignupResponse response = signupService.signup(token, new SignupRequest(PASSWORD, nickname("Ok")));

        // then: 저장된 행
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT public_id, email, password_hash, nickname, role, status, provider, created_at FROM users WHERE email = ?",
                email);
        assertThat(countUsersByEmail(email)).isEqualTo(1);
        assertThat(row.get("public_id")).isEqualTo(response.userId());
        assertThat(row.get("nickname")).isEqualTo(nickname("Ok"));
        assertThat(row).containsEntry("role", "USER").containsEntry("status", "ACTIVE").containsEntry("provider", "LOCAL");
        String hash = (String) row.get("password_hash");
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();

        // then: 응답
        assertThat(response.email()).isEqualTo(email);
        assertThat(response.nickname()).isEqualTo(nickname("Ok"));
        assertThat(response.roles()).containsExactly("USER");
        assertThat(response.createdAt()).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT created_at FROM users WHERE email = ?", OffsetDateTime.class, email));
        assertThat(response.toString()).doesNotContain(PASSWORD).doesNotContain(hash);

        // then: 원문 비밀번호는 이 테스트의 로그 출력에 없다
        assertThat(output).doesNotContain(PASSWORD).doesNotContain(hash);

        // then: 컨텍스트는 소비되어 다시 쓸 수 없다
        assertThat(signupContextRepository.findEmail(token)).isEmpty();
        assertErrorCode(() -> signupService.signup(token, new SignupRequest(PASSWORD, nickname("Re"))),
                UserErrorCode.EMAIL_SIGNUP_CONTEXT_INVALID);
        assertThat(countUsersByEmail(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("M04-02: 이미 가입된 이메일의 컨텍스트는 DUPLICATE_EMAIL이고 회원이 늘지 않으며 컨텍스트가 소비된다")
    void rejectsRegisteredEmail() {
        // given
        String email = email("dup");
        signupService.signup(issueContext(email), new SignupRequest(PASSWORD, nickname("Dup")));
        String token = issueContext(email);

        // when & then
        assertErrorCode(() -> signupService.signup(token, new SignupRequest(PASSWORD, nickname("Dup2"))),
                UserErrorCode.DUPLICATE_EMAIL);
        assertThat(countUsersByEmail(email)).isEqualTo(1);
        assertThat(signupContextRepository.findEmail(token)).isEmpty();
    }

    @Test
    @DisplayName("M04-02: 같은 컨텍스트로 동시에 가입하면 회원은 1명이고 나머지는 모두 DUPLICATE_EMAIL이다")
    void allowsOneSignupForConcurrentSameContext() throws Exception {
        // given: 닉네임은 요청마다 달라 이메일 충돌만 생긴다
        String email = email("race");
        String token = issueContext(email);
        List<Callable<SignupResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            String nickname = nickname("R" + i);
            tasks.add(() -> signupService.signup(token, new SignupRequest(PASSWORD, nickname)));
        }

        // when: 사전 검사와 저장 사이에 Argon2 해시가 있어 여러 요청이 사전 검사를 함께 통과하고 DB 제약에서 갈린다
        List<Object> results = runConcurrently(tasks);

        // then: 다른 예외(제약 위반 그대로 전파 등)가 하나도 없다
        assertThat(results).filteredOn(SignupResponse.class::isInstance).hasSize(1);
        assertThat(errorCodes(results)).hasSize(CONCURRENT_REQUESTS - 1)
                .containsOnly(UserErrorCode.DUPLICATE_EMAIL);
        assertThat(countUsersByEmail(email)).isEqualTo(1);
        assertThat(signupContextRepository.findEmail(token)).isEmpty();
    }

    @Test
    @DisplayName("M04-03: 대소문자만 다른 닉네임은 DUPLICATE_NICKNAME이고 컨텍스트가 남아 다른 닉네임으로 다시 가입할 수 있다")
    void rejectsDuplicateNicknameIgnoringCase() {
        // given
        signupService.signup(issueContext(email("first")), new SignupRequest(PASSWORD, nickname("Nick")));
        String email = email("second");
        String token = issueContext(email);

        // when & then
        assertErrorCode(() -> signupService.signup(token, new SignupRequest(PASSWORD, nickname("NICK"))),
                UserErrorCode.DUPLICATE_NICKNAME);
        assertThat(countUsersByEmail(email)).isZero();
        assertThat(signupContextRepository.findEmail(token)).contains(email);

        SignupResponse retried = signupService.signup(token, new SignupRequest(PASSWORD, nickname("Oth")));
        assertThat(retried.email()).isEqualTo(email);
        assertThat(countUsersByEmail(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("M04-03: 서로 다른 이메일이 같은 닉네임으로 동시에 가입하면 1명만 저장되고 나머지는 DUPLICATE_NICKNAME이며 컨텍스트가 남는다")
    void allowsOneNicknameForConcurrentSignups() throws Exception {
        // given: 대소문자만 다른 같은 닉네임
        List<String> tokens = new ArrayList<>();
        List<Callable<SignupResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            String token = issueContext(email("nick" + i));
            String nickname = i % 2 == 0 ? nickname("Same") : nickname("SAME");
            tokens.add(token);
            tasks.add(() -> signupService.signup(token, new SignupRequest(PASSWORD, nickname)));
        }

        // when
        List<Object> results = runConcurrently(tasks);

        // then
        assertThat(results).filteredOn(SignupResponse.class::isInstance).hasSize(1);
        assertThat(errorCodes(results)).hasSize(CONCURRENT_REQUESTS - 1)
                .containsOnly(UserErrorCode.DUPLICATE_NICKNAME);
        assertThat(countUsersByNickname(nickname("Same"))).isEqualTo(1);
        // 실패한 요청의 컨텍스트는 남아 있다(성공한 1건만 소비)
        assertThat(tokens).filteredOn(token -> signupContextRepository.findEmail(token).isPresent())
                .hasSize(CONCURRENT_REQUESTS - 1);
    }
}
