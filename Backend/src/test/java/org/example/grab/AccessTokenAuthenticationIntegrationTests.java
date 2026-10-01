package org.example.grab;

import jakarta.servlet.http.Cookie;
import org.example.grab.domain.drop.service.DropService;
import org.example.grab.domain.order.service.OrderQueryService;
import org.example.grab.global.security.AuthRole;
import org.example.grab.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// S1·S5: 실제 SecurityFilterChain에서 access_token 쿠키의 sub(public_id)가 users.id·승인된 sellers.id로 바뀌어 컨트롤러까지 전달된다
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AccessTokenAuthenticationIntegrationTests {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 실제 동작은 그대로 두고, 컨트롤러가 넘긴 회원 ID만 확인한다
    @MockitoSpyBean
    private OrderQueryService orderQueryService;

    @MockitoSpyBean
    private DropService dropService;

    private Long userId;
    private UUID publicId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        Map<String, Object> user = insertUser();
        userId = (Long) user.get("id");
        publicId = (UUID) user.get("public_id");
        // 다른 회원이 있어도 토큰 주인의 ID만 쓰이는지 보기 위해 하나 더 만든다
        otherUserId = (Long) insertUser().get("id");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM sellers WHERE user_id IN (?, ?)", userId, otherUserId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", userId, otherUserId);
    }

    @Test
    @DisplayName("유효한 쿠키로 보호 API에 접근하면 200이고, sub(public_id)에 해당하는 users.id가 컨트롤러에 전달된다")
    void resolvesUsersIdFromAccessTokenSubject() throws Exception {
        // given
        String token = jwtProvider.issue(publicId, Set.of(AuthRole.USER)).value();

        // when, then
        mockMvc.perform(get("/api/v1/orders").cookie(new Cookie("access_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(orderQueryService).findMyOrders(eq(userId), isNull(), eq(0), eq(20));
    }

    @Test
    @DisplayName("서명은 유효하지만 sub에 해당하는 회원이 없으면 401 AUTHENTICATION_REQUIRED")
    void rejectsTokenOfUnknownMember() throws Exception {
        // given: 탈퇴·삭제 등으로 회원 행이 없는 경우
        String token = jwtProvider.issue(UUID.randomUUID(), Set.of(AuthRole.USER)).value();

        // when, then
        mockMvc.perform(get("/api/v1/orders").cookie(new Cookie("access_token", token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        verify(orderQueryService, never()).findMyOrders(anyLong(), isNull(), eq(0), eq(20));
    }

    // S5: JWT의 SELLER는 발급 시점 값이다. 판매자 API는 요청마다 sellers.status = APPROVED를 다시 확인한다(M00-03)
    @Test
    @DisplayName("SELLER 토큰을 발급받은 뒤 승인이 취소되면, 같은 토큰으로도 판매자 API는 403 ACCESS_DENIED")
    void rejectsSellerTokenAfterApprovalRevoked() throws Exception {
        // given: 승인된 판매자에게 SELLER 토큰을 발급한다
        Long sellerId = insertApprovedSeller(userId, otherUserId);
        String token = jwtProvider.issue(publicId, Set.of(AuthRole.USER, AuthRole.SELLER)).value();

        // when, then: 승인 상태에서는 그 판매자의 sellers.id로 조회한다
        mockMvc.perform(get("/api/v1/seller/drops").cookie(new Cookie("access_token", token)))
                .andExpect(status().isOk());
        verify(dropService).findSellerDrops(eq(sellerId), isNull(), eq(0), eq(20));

        // given: 토큰이 만료되기 전에 승인이 취소된다
        jdbcTemplate.update("""
                UPDATE sellers SET status = 'REJECTED', rejection_reason = '승인 취소', reviewed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, sellerId);

        // when, then: 토큰에는 여전히 SELLER가 있지만 DB 조회에서 막힌다
        mockMvc.perform(get("/api/v1/seller/drops").cookie(new Cookie("access_token", token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verify(dropService, times(1)).findSellerDrops(anyLong(), isNull(), eq(0), eq(20));
    }

    @Test
    @DisplayName("SELLER 토큰이지만 판매자 신청이 승인 전(PENDING)이면 판매자 API는 403 ACCESS_DENIED")
    void rejectsSellerTokenOfPendingSeller() throws Exception {
        // given
        jdbcTemplate.update("INSERT INTO sellers (user_id, brand_name, contact_email) VALUES (?, '브랜드', ?)",
                userId, "contact-" + userId + "@example.com");
        String token = jwtProvider.issue(publicId, Set.of(AuthRole.USER, AuthRole.SELLER)).value();

        // when, then
        mockMvc.perform(get("/api/v1/seller/drops").cookie(new Cookie("access_token", token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verify(dropService, never()).findSellerDrops(anyLong(), isNull(), eq(0), eq(20));
    }

    // ck_sellers_review: 승인 상태는 reviewed_by·reviewed_at이 필요하다
    private Long insertApprovedSeller(Long sellerUserId, Long reviewerId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO sellers (user_id, brand_name, contact_email, status, reviewed_by, reviewed_at)
                VALUES (?, '브랜드', ?, 'APPROVED', ?, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, sellerUserId, "contact-" + sellerUserId + "@example.com", reviewerId);
    }

    private Map<String, Object> insertUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbcTemplate.queryForMap(
                "INSERT INTO users (email, password_hash, nickname) VALUES (?, 'password', ?) RETURNING id, public_id",
                "user-" + suffix + "@example.com",
                "회원-" + suffix);
    }
}
