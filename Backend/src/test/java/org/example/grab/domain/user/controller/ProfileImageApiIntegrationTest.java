package org.example.grab.domain.user.controller;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.example.grab.domain.user.service.ProfileImageCommandService;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.JwtProvider;
import org.example.grab.global.storage.ProfileImageStorage;
import org.example.grab.global.storage.SignedUploadUrl;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-130 M05-03: 실제 SecurityFilterChain·Controller·서비스·PostgreSQL로 프로필 이미지 업로드 URL 발급·등록·삭제를 확인한다.
    Supabase는 부르지 않는다. 저장소만 MockitoBean으로 바꾸고 공개 URL은 실제 클라이언트와 같은 규칙으로 만든다.
    삭제 호출 시점에 다른 스레드(다른 연결)로 DB 값을 읽어 커밋 뒤에만 지우는지 본다(M00-04 1번).
    @SpringBootTest는 롤백하지 않으므로 테스트마다 고유한 이메일로 회원을 만들고 직접 지운다(UserMeApiIntegrationTest와 같음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers
class ProfileImageApiIntegrationTest {

    private static final String UPLOAD_URL_PATH = "/api/v1/users/me/profile-image/upload-url";
    private static final String PROFILE_IMAGE_PATH = "/api/v1/users/me/profile-image";
    private static final String ME_PATH = "/api/v1/users/me";
    private static final String SELLER_UPLOAD_PATH = "/api/v1/uploads/images/presigned-url";
    private static final String CSRF_PATH = "/api/v1/auth/csrf";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";
    private static final String PUBLIC_BASE = "https://project.supabase.co/storage/v1/object/public/profile-images/";
    private static final String UPLOAD_BODY = "{\"fileName\":\"me.png\",\"contentType\":\"image/png\",\"fileSize\":1024}";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private ProfileImageCommandService profileImageCommandService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private ProfileImageStorage profileImageStorage;

    private final String run = UUID.randomUUID().toString().substring(0, 6);
    // 삭제 호출마다 (객체 키, 그 순간 다른 연결로 읽은 profile_image_url)를 기록한다
    private final List<String[]> deletions = new CopyOnWriteArrayList<>();
    private ExecutorService executor;
    private ExecutorService readExecutor;

    private record Member(Long id, UUID publicId, String token) {
    }

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(3);
        readExecutor = Executors.newCachedThreadPool();
        given(profileImageStorage.publicUrl(anyString())).willAnswer(invocation -> PUBLIC_BASE + invocation.getArgument(0));
        given(profileImageStorage.createSignedUploadUrl(anyString())).willAnswer(invocation -> new SignedUploadUrl(
                "https://project.supabase.co/storage/v1/object/upload/sign/profile-images/"
                        + invocation.getArgument(0) + "?token=signed",
                PUBLIC_BASE + invocation.getArgument(0)));
        willAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String ownerPublicId = key.split("/")[1];
            // 요청 스레드의 연결은 커밋 전이라면 자기 변경을 보므로, 다른 스레드(다른 연결)에서 읽는다
            String visible = readExecutor.submit(() -> jdbcTemplate.queryForObject(
                    "SELECT profile_image_url FROM users WHERE public_id = ?::uuid", String.class, ownerPublicId))
                    .get(10, TimeUnit.SECONDS);
            deletions.add(new String[]{key, visible});
            return null;
        }).given(profileImageStorage).delete(anyString());
    }

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        readExecutor.shutdownNow();
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", "pimg-" + run + "-%");
    }

    private Member insertUser(String name, String status, Set<AuthRole> roles) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO users (email, password_hash, nickname, status) VALUES (?, 'encoded-password', ?, ?)
                RETURNING id, public_id
                """, "pimg-" + run + "-" + name + "@example.com", "p" + run + name, status);
        UUID publicId = (UUID) row.get("public_id");
        return new Member((Long) row.get("id"), publicId, jwtProvider.issue(publicId, roles).value());
    }

    private Member activeUser(String name) {
        return insertUser(name, "ACTIVE", Set.of(AuthRole.USER));
    }

    private static String ownedKey(Member member, String imageId) {
        return "profiles/" + member.publicId() + "/" + imageId + ".png";
    }

    private static String ownedUrl(Member member, String imageId) {
        return PUBLIC_BASE + ownedKey(member, imageId);
    }

    private static String imageId() {
        return UUID.randomUUID().toString();
    }

    private String storedUrl(Member member) {
        return jdbcTemplate.queryForObject("SELECT profile_image_url FROM users WHERE id = ?", String.class, member.id());
    }

    private void storeUrl(Member member, String url) {
        jdbcTemplate.update("UPDATE users SET profile_image_url = ? WHERE id = ?", url, member.id());
    }

    private String issueCsrfToken() throws Exception {
        Cookie cookie = mockMvc.perform(get(CSRF_PATH)).andReturn().getResponse().getCookie(XSRF_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    // token이 null이면 인증 쿠키를, withCsrf가 false면 CSRF 토큰을 보내지 않는다
    private ResultActions perform(MockHttpServletRequestBuilder request, String token, boolean withCsrf) throws Exception {
        if (withCsrf) {
            String csrf = issueCsrfToken();
            request.cookie(new Cookie(XSRF_COOKIE, csrf)).header(XSRF_HEADER, csrf);
        }
        if (token != null) {
            request.cookie(new Cookie("access_token", token));
        }
        return mockMvc.perform(request);
    }

    private ResultActions putImage(String token, String imageUrl) throws Exception {
        return perform(put(PROFILE_IMAGE_PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageUrl\":\"" + imageUrl + "\"}"), token, true);
    }

    private ResultActions deleteImage(String token) throws Exception {
        return perform(delete(PROFILE_IMAGE_PATH), token, true);
    }

    private ResultActions postUploadUrl(String token) throws Exception {
        return perform(post(UPLOAD_URL_PATH).contentType(MediaType.APPLICATION_JSON).content(UPLOAD_BODY), token, true);
    }

    private static void assertError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    // 업로드 URL 발급

    @Test
    @DisplayName("업로드 URL 발급은 본인 public_id 경로의 서명 URL·공개 URL을 돌려준다")
    void issuesUploadUrl() throws Exception {
        // given
        Member member = activeUser("up");

        // when
        String body = postUploadUrl(member.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();

        // then
        String imageId = JsonPath.read(body, "$.data.imageId");
        assertThat((String) JsonPath.read(body, "$.data.imageUrl")).isEqualTo(ownedUrl(member, imageId));
        assertThat((String) JsonPath.read(body, "$.data.uploadUrl")).contains(ownedKey(member, imageId));
        assertThat((String) JsonPath.read(body, "$.data.expiresAt")).isNotBlank();
    }

    @Test
    @DisplayName("허용하지 않는 형식은 400 VALIDATION_FAILED(contentType)이고 서명 URL을 만들지 않는다")
    void rejectsUnsupportedUploadType() throws Exception {
        // given
        Member member = activeUser("gif");

        // when
        ResultActions result = perform(post(UPLOAD_URL_PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"me.gif\",\"contentType\":\"image/gif\",\"fileSize\":1024}"), member.token(), true);

        // then
        assertError(result, 400, "VALIDATION_FAILED");
        result.andExpect(jsonPath("$.error.fieldErrors[0].field").value("contentType"));
        verify(profileImageStorage, never()).createSignedUploadUrl(anyString());
    }

    // 등록·삭제

    @Test
    @DisplayName("발급받은 imageUrl로 PUT하면 두 필드로 응답하고 GET users/me에 반영되며 지울 객체는 없다")
    void registersImage() throws Exception {
        // given: 발급 API로 받은 URL을 그대로 쓴다
        Member member = activeUser("reg");
        String imageUrl = JsonPath.read(
                postUploadUrl(member.token()).andReturn().getResponse().getContentAsString(), "$.data.imageUrl");

        // when
        String body = putImage(member.token(), imageUrl)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(member.publicId().toString()))
                .andExpect(jsonPath("$.data.profileImageUrl").value(imageUrl))
                .andReturn().getResponse().getContentAsString();

        // then
        Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data).containsOnlyKeys("userId", "profileImageUrl");
        perform(get(ME_PATH), member.token(), false)
                .andExpect(jsonPath("$.data.profileImageUrl").value(imageUrl));
        assertThat(deletions).isEmpty();
    }

    @Test
    @DisplayName("교체하면 커밋된 뒤 이전 객체를 지운다")
    void replacingDeletesPreviousAfterCommit() throws Exception {
        // given
        Member member = activeUser("rep");
        String first = imageId();
        String second = imageId();
        putImage(member.token(), ownedUrl(member, first)).andExpect(status().isOk());

        // when
        putImage(member.token(), ownedUrl(member, second)).andExpect(status().isOk());

        // then: 삭제 순간 다른 연결에서도 새 값이 보인다 = 커밋 뒤에 호출됐다
        assertThat(deletions).singleElement().satisfies(deletion -> {
            assertThat(deletion[0]).isEqualTo(ownedKey(member, first));
            assertThat(deletion[1]).isEqualTo(ownedUrl(member, second));
        });
    }

    @Test
    @DisplayName("같은 URL을 다시 PUT하면 200이고 객체를 지우지 않는다")
    void reRegisteringSameUrlKeepsObject() throws Exception {
        // given
        Member member = activeUser("sam");
        String url = ownedUrl(member, imageId());
        putImage(member.token(), url).andExpect(status().isOk());

        // when & then
        putImage(member.token(), url).andExpect(status().isOk());
        assertThat(deletions).isEmpty();
    }

    @Test
    @DisplayName("DELETE하면 204이고 GET users/me의 profileImageUrl이 null이며 커밋 뒤 객체를 지운다")
    void removesImage() throws Exception {
        // given
        Member member = activeUser("del");
        String imageId = imageId();
        putImage(member.token(), ownedUrl(member, imageId)).andExpect(status().isOk());

        // when
        deleteImage(member.token()).andExpect(status().isNoContent());

        // then
        perform(get(ME_PATH), member.token(), false)
                .andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()));
        assertThat(storedUrl(member)).isNull();
        assertThat(deletions).singleElement().satisfies(deletion -> {
            assertThat(deletion[0]).isEqualTo(ownedKey(member, imageId));
            assertThat(deletion[1]).isNull();
        });
    }

    @Test
    @DisplayName("이미지가 없을 때 DELETE해도 204이고 지울 객체가 없다")
    void removingWithoutImage() throws Exception {
        // given
        Member member = activeUser("non");

        // when & then
        deleteImage(member.token()).andExpect(status().isNoContent());
        assertThat(deletions).isEmpty();
    }

    @Test
    @DisplayName("본인 발급이 아닌 URL은 400 VALIDATION_FAILED(imageUrl)이고 값과 객체가 그대로다")
    void rejectsNotOwnedUrl() throws Exception {
        // given
        Member member = activeUser("bad");
        Member other = activeUser("oth");
        String current = ownedUrl(member, imageId());
        storeUrl(member, current);

        // when & then
        for (String url : List.of("https://cdn.example.com/images/profile-uuid.jpg",
                ownedUrl(other, imageId()),
                "https://project.supabase.co/storage/v1/object/public/drop-images/images/" + imageId() + ".png",
                ownedUrl(member, imageId()) + "?v=1")) {
            ResultActions result = putImage(member.token(), url);
            assertError(result, 400, "VALIDATION_FAILED");
            result.andExpect(jsonPath("$.error.fieldErrors[0].field").value("imageUrl"));
        }
        assertThat(storedUrl(member)).isEqualTo(current);
        assertThat(deletions).isEmpty();
    }

    @Test
    @DisplayName("imageUrl이 빠지거나 비면 400 VALIDATION_FAILED, 본문 형식 오류는 400 INVALID_REQUEST다")
    void rejectsMissingImageUrl() throws Exception {
        // given
        Member member = activeUser("mis");

        // when & then
        for (String json : List.of("{}", "{\"imageUrl\":null}", "{\"imageUrl\":\"\"}", "{\"imageUrl\":\"  \"}")) {
            assertError(perform(put(PROFILE_IMAGE_PATH).contentType(MediaType.APPLICATION_JSON).content(json),
                    member.token(), true), 400, "VALIDATION_FAILED");
        }
        assertError(perform(put(PROFILE_IMAGE_PATH).contentType(MediaType.APPLICATION_JSON).content("{"),
                member.token(), true), 400, "INVALID_REQUEST");
    }

    // M00-04 1번: 롤백되면 이전 객체를 지우지 않는다
    @Test
    @DisplayName("교체 트랜잭션이 롤백되면 값이 그대로이고 이전 객체를 지우지 않는다")
    void rollbackDoesNotDelete() {
        // given
        Member member = activeUser("rb");
        String current = ownedUrl(member, imageId());
        storeUrl(member, current);

        // when: 서비스가 이벤트를 발행한 뒤 바깥 트랜잭션이 롤백된다
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            profileImageCommandService.register(member.id(), ownedUrl(member, imageId()));
            status.setRollbackOnly();
        });

        // then
        assertThat(storedUrl(member)).isEqualTo(current);
        verify(profileImageStorage, never()).delete(anyString());
    }

    // M00-04 5번: 같은 회원의 동시 PUT은 회원 행 잠금으로 줄을 서서, 사용 중인 객체를 지우거나 같은 객체를 두 번 지우지 않는다
    @Test
    @DisplayName("같은 회원의 동시 PUT은 차례로 처리돼 최종 값의 객체는 남고 이전 값들만 한 번씩 지운다")
    void concurrentPutsKeepFinalObject() throws Exception {
        // given: 다른 트랜잭션이 회원 행을 잠근 동안 두 PUT을 보내 둘 다 잠금을 기다리게 한다
        Member member = activeUser("con");
        String initial = imageId();
        String b = imageId();
        String c = imageId();
        storeUrl(member, ownedUrl(member, initial));
        int baseline = waitingForLock();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    jdbcTemplate.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, member.id());
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        String csrfB = issueCsrfToken();
        String csrfC = issueCsrfToken();

        // when
        Future<Integer> putB = executor.submit(() -> putWithCsrf(member.token(), csrfB, ownedUrl(member, b)));
        Future<Integer> putC = executor.submit(() -> putWithCsrf(member.token(), csrfC, ownedUrl(member, c)));
        awaitWaitingForLock(baseline + 2);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        // then
        assertThat(putB.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(putC.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        String finalUrl = storedUrl(member);
        String finalId = finalUrl.equals(ownedUrl(member, b)) ? b : c;
        String overwrittenId = finalId.equals(b) ? c : b;
        assertThat(deletions).extracting(deletion -> deletion[0])
                .containsExactlyInAnyOrder(ownedKey(member, initial), ownedKey(member, overwrittenId))
                .doesNotContain(ownedKey(member, finalId));
    }

    private int putWithCsrf(String token, String csrf, String imageUrl) throws Exception {
        return mockMvc.perform(put(PROFILE_IMAGE_PATH)
                        .cookie(new Cookie(XSRF_COOKIE, csrf), new Cookie("access_token", token))
                        .header(XSRF_HEADER, csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\":\"" + imageUrl + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    // 공통 거부

    @Test
    @DisplayName("비로그인은 세 API 모두 401 AUTHENTICATION_REQUIRED다")
    void rejectsAnonymous() throws Exception {
        assertError(postUploadUrl(null), 401, "AUTHENTICATION_REQUIRED");
        assertError(putImage(null, PUBLIC_BASE + "x.png"), 401, "AUTHENTICATION_REQUIRED");
        assertError(deleteImage(null), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("정지·탈퇴 회원은 세 API 모두 401이고 값과 객체가 그대로다")
    void rejectsInactiveMembers() throws Exception {
        for (String status : List.of("SUSPENDED", "WITHDRAWN")) {
            // given
            Member member = insertUser(status.substring(0, 3).toLowerCase(), status, Set.of(AuthRole.USER));
            String current = ownedUrl(member, imageId());
            storeUrl(member, current);

            // when & then
            assertError(postUploadUrl(member.token()), 401, "AUTHENTICATION_REQUIRED");
            assertError(putImage(member.token(), ownedUrl(member, imageId())), 401, "AUTHENTICATION_REQUIRED");
            assertError(deleteImage(member.token()), 401, "AUTHENTICATION_REQUIRED");
            assertThat(storedUrl(member)).isEqualTo(current);
        }
        verify(profileImageStorage, never()).createSignedUploadUrl(anyString());
        assertThat(deletions).isEmpty();
    }

    @Test
    @DisplayName("USER 권한이 없는 토큰은 세 API 모두 403 ACCESS_DENIED다")
    void rejectsTokenWithoutUserRole() throws Exception {
        // given
        Member member = insertUser("adm", "ACTIVE", Set.of(AuthRole.ADMIN));
        String current = ownedUrl(member, imageId());
        storeUrl(member, current);

        // when & then
        assertError(postUploadUrl(member.token()), 403, "ACCESS_DENIED");
        assertError(putImage(member.token(), ownedUrl(member, imageId())), 403, "ACCESS_DENIED");
        assertError(deleteImage(member.token()), 403, "ACCESS_DENIED");
        assertThat(storedUrl(member)).isEqualTo(current);
    }

    @Test
    @DisplayName("CSRF 토큰이 없으면 세 API 모두 403 ACCESS_DENIED이고 값과 객체가 그대로다")
    void rejectsMissingCsrf() throws Exception {
        // given
        Member member = activeUser("csr");
        String current = ownedUrl(member, imageId());
        storeUrl(member, current);

        // when & then
        assertError(perform(post(UPLOAD_URL_PATH).contentType(MediaType.APPLICATION_JSON).content(UPLOAD_BODY),
                member.token(), false), 403, "ACCESS_DENIED");
        assertError(perform(put(PROFILE_IMAGE_PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageUrl\":\"" + ownedUrl(member, imageId()) + "\"}"), member.token(), false), 403, "ACCESS_DENIED");
        assertError(perform(delete(PROFILE_IMAGE_PATH), member.token(), false), 403, "ACCESS_DENIED");
        assertThat(storedUrl(member)).isEqualTo(current);
        assertThat(deletions).isEmpty();
    }

    // M04-01: 판매자 DROP 이미지 업로드 규칙은 그대로다
    @Test
    @DisplayName("USER 토큰으로 판매자 DROP 이미지 업로드 URL을 요청하면 계속 403이다")
    void sellerUploadStillRequiresSeller() throws Exception {
        // given
        Member member = activeUser("sel");

        // when & then
        assertError(perform(post(SELLER_UPLOAD_PATH).contentType(MediaType.APPLICATION_JSON).content(UPLOAD_BODY),
                member.token(), true), 403, "ACCESS_DENIED");
    }

    private int waitingForLock() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                Integer.class);
        return count == null ? 0 : count;
    }

    private void awaitWaitingForLock(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (waitingForLock() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("행 잠금 대기에 들어간 트랜잭션이 " + expected + "개가 되지 않았습니다.");
            }
            Thread.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 신호를 받지 못했습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
