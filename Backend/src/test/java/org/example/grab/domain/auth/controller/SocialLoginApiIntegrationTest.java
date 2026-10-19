package org.example.grab.domain.auth.controller;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.example.grab.domain.auth.repository.RefreshTokenRepository;
import org.example.grab.domain.user.repository.OAuth2SignupContextRepository;
import org.example.grab.domain.user.service.SocialAccountService;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.converter.ClaimTypeConverter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-46 M02-02: 실제 SecurityFilterChain·OAuth2 필터·Controller·서비스·PostgreSQL·Redis로 소셜 로그인(MEMBER_AUTH 1.4~1.6)을 확인한다.
    제공자 대역(M00-10):
    - 시작 요청은 실제 필터가 인가 요청(state·nonce·PKCE)을 만들어 세션에 저장한다. 콜백은 그 세션과 Location의 state로 호출한다
    - 토큰 교환(OAuth2AccessTokenResponseClient)만 mock으로 바꿔 테스트 키로 서명한 ID 토큰을 돌려준다
    - ID 토큰 디코더(JwtDecoderFactory)는 JWKS 대신 테스트 공개키를 쓰고, 검증기는 Spring 기본과 같은 OidcIdTokenValidator(iss·aud·exp)다.
      nonce 검증은 운영과 같은 Spring 기본 구현이 그대로 돈다
    - KAKAO의 OIDC userinfo 호출(OidcUserService 안의 OAuth2UserService)만 mock으로 바꾼다. userinfo sub와 ID 토큰 sub의 일치 검사,
      claim 병합은 Spring 기본 OidcUserService가 한다. GOOGLE은 userinfo 주소가 없어 호출하지 않는다
    M00-10은 OidcUserService를 대역하려 했으나, Spring은 그 전에 ID 토큰을 디코딩하므로 디코더를 바꾸는 쪽이 대역 범위가 더 작다.
    쿠키는 ResponseCookie 문자열로 Set-Cookie 헤더에 쓰이므로 헤더 문자열로 속성을 단언한다. 쿠키 이름은 명세 문자열로 단언한다.
    @SpringBootTest는 롤백하지 않으므로 테스트마다 고유한 이메일·식별자로 회원을 만들고 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SocialLoginApiIntegrationTest.ProviderIdTokenConfig.class})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class SocialLoginApiIntegrationTest {

    private static final String BASE_PATH = "/api/v1/auth/oauth2";
    private static final String SIGNUP_PATH = BASE_PATH + "/signup";
    private static final String CSRF_PATH = "/api/v1/auth/csrf";
    private static final String ME_PATH = "/api/v1/users/me";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";
    private static final String SIGNUP_COOKIE = "oauth2_signup_token";
    private static final String FRONTEND_CALLBACK = "http://localhost:5173/#/auth/callback";
    private static final String SIGNUP_CONTEXT_KEYS = "auth:oauth2-signup-context:*";
    // 대역 제공자가 쓰는 값. 로그·리다이렉트 URL에 나오지 않아야 한다
    private static final String AUTHORIZATION_CODE = "test-authorization-code-value";
    private static final String PROVIDER_ACCESS_TOKEN = "test-provider-access-token-value";
    private static final String GOOGLE_CLIENT_SECRET = "test-google-client-secret";
    private static final String KAKAO_CLIENT_SECRET = "test-kakao-client-secret";

    // 제공자가 ID 토큰 서명에 쓰는 키(디코더가 신뢰)와 그 밖의 키(서명 위조)
    static final RSAKey PROVIDER_KEY = generateKey("provider");
    private static final RSAKey FORGED_KEY = generateKey("forged");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // 컨테이너 Redis는 인증이 없다. 빈 비밀번호는 "비밀번호 없음"으로 처리된다
        registry.add("spring.data.redis.password", () -> "");
    }

    /*
        운영의 OidcIdTokenDecoderFactory는 jwkSetUri에서 공개키를 받는다. 테스트는 같은 claim 변환·검증기에 테스트 공개키만 넣는다.
        oauth2Login은 JwtDecoderFactory<ClientRegistration> 빈이 있으면 그것을 쓴다(Spring Security 7.1).
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderIdTokenConfig {

        @Bean
        JwtDecoderFactory<ClientRegistration> providerIdTokenDecoderFactory() {
            return registration -> {
                NimbusJwtDecoder decoder;
                try {
                    decoder = NimbusJwtDecoder.withPublicKey(PROVIDER_KEY.toRSAPublicKey()).build();
                } catch (JOSEException e) {
                    throw new IllegalStateException(e);
                }
                decoder.setClaimSetConverter(new ClaimTypeConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverters()));
                decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(), new OidcIdTokenValidator(registration)));
                return decoder;
            };
        }

        // oauth2Login은 OAuth2UserService<OidcUserRequest, OidcUser> 빈이 있으면 그것을 쓴다. userinfo 호출만 mock으로 넘긴다
        @Bean
        OAuth2UserService<OidcUserRequest, OidcUser> providerOidcUserService(
                OAuth2UserService<OAuth2UserRequest, OAuth2User> userInfoService) {
            OidcUserService service = new OidcUserService();
            service.setOauth2UserService(userInfoService);
            return service;
        }
    }

    @MockitoBean
    private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient;
    @MockitoBean
    private OAuth2UserService<OAuth2UserRequest, OAuth2User> userInfoService;
    @MockitoSpyBean
    private OAuth2SignupContextRepository signupContextRepository;
    @MockitoSpyBean
    private RefreshTokenRepository refreshTokenRepository;
    @MockitoSpyBean
    private SocialAccountService socialAccountService;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    // 테스트가 쓴 이메일·식별자·닉네임의 공통 값. 다른 테스트의 데이터와 섞이지 않게 한다(16진수 6자)
    private final String run = UUID.randomUUID().toString().substring(0, 6);

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ? OR provider_user_id LIKE ?",
                "social-api-" + run + "-%", "sub-" + run + "-%");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    // 테스트 데이터

    private String email(String name) {
        return "social-api-" + run + "-" + name + "@example.com";
    }

    private String sub(String name) {
        return "sub-" + run + "-" + name;
    }

    // 닉네임 규칙(2~10자, 영문·숫자·밑줄) 안에서 실행마다 다른 값
    private String nickname(String suffix) {
        return "n" + run + suffix;
    }

    private Map<String, Object> insertLocalUser(String name) {
        return jdbcTemplate.queryForMap("""
                INSERT INTO users (email, password_hash, nickname) VALUES (?, 'hash', ?) RETURNING id, public_id
                """, email(name), nickname(name));
    }

    private Map<String, Object> insertSocialUser(String provider, String name, String status) {
        return jdbcTemplate.queryForMap("""
                INSERT INTO users (email, nickname, provider, provider_user_id, status) VALUES (?, ?, ?, ?, ?)
                RETURNING id, public_id
                """, email(name), nickname(name), provider, sub(name), status);
    }

    private Map<String, Object> userRow(Object id) {
        return jdbcTemplate.queryForMap(
                "SELECT email, nickname, provider, provider_user_id, status, updated_at FROM users WHERE id = ?", id);
    }

    private Integer countUsersBySub(String name) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE provider_user_id = ?", Integer.class, sub(name));
    }

    private Integer countUsersByEmail(String name) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email(name));
    }

    private int countSignupContexts() {
        return redisTemplate.keys(SIGNUP_CONTEXT_KEYS).size();
    }

    // 대역 제공자

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    // 시작 요청이 세션에 저장한 인가 요청과, 제공자 인증 화면 주소(Location)의 쿼리
    private record Authorization(MockHttpSession session, MultiValueMap<String, String> query) {

        String state() {
            return param("state");
        }

        // ID 토큰의 nonce claim은 인가 요청에 보낸 nonce(원문의 해시) 값이어야 한다
        String nonce() {
            return param(OidcParameterNames.NONCE);
        }

        String param(String name) {
            String value = query.getFirst(name);
            return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }

    private Authorization start(String provider) throws Exception {
        MvcResult result = mockMvc.perform(get(BASE_PATH + "/" + provider))
                .andExpect(status().isFound())
                .andReturn();
        String location = result.getResponse().getRedirectedUrl();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(location).isNotNull();
        assertThat(session).isNotNull();
        return new Authorization(session, UriComponentsBuilder.fromUriString(location).build().getQueryParams());
    }

    private ClientRegistration registration(String provider) {
        return clientRegistrationRepository.findByRegistrationId(provider);
    }

    // 제공자가 발급하는 ID 토큰의 claim. 각 테스트가 필요한 값만 바꾼다
    private Map<String, Object> idTokenClaims(String provider, Authorization authorization, String subName,
                                              String email, Object emailVerified) {
        ClientRegistration registration = registration(provider);
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", registration.getProviderDetails().getIssuerUri());
        claims.put("aud", registration.getClientId());
        claims.put("sub", sub(subName));
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(300).getEpochSecond());
        claims.put("nonce", authorization.nonce());
        if (email != null) {
            claims.put("email", email);
        }
        if (emailVerified != null) {
            claims.put("email_verified", emailVerified);
        }
        return claims;
    }

    private static String sign(Map<String, Object> claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    JWTClaimsSet.parse(claims));
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // 토큰 교환 응답. idToken이 null이면 id_token 없이 응답한다
    private void stubTokenResponse(String idToken) {
        OAuth2AccessTokenResponse.Builder response = OAuth2AccessTokenResponse.withToken(PROVIDER_ACCESS_TOKEN)
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(300);
        if (idToken != null) {
            response.additionalParameters(Map.of(OidcParameterNames.ID_TOKEN, idToken));
        }
        when(tokenResponseClient.getTokenResponse(any())).thenReturn(response.build());
    }

    // KAKAO OIDC userinfo 응답. null인 값은 응답에 넣지 않는다
    private static Map<String, Object> userInfoClaims(String sub, String email, Object emailVerified) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", sub);
        if (email != null) {
            claims.put("email", email);
        }
        if (emailVerified != null) {
            claims.put("email_verified", emailVerified);
        }
        return claims;
    }

    private void stubUserInfo(Map<String, Object> claims) {
        when(userInfoService.loadUser(any())).thenReturn(new DefaultOAuth2User(List.of(), claims, "sub"));
    }

    private MockHttpServletResponse callback(String provider, Authorization authorization) throws Exception {
        return mockMvc.perform(get(BASE_PATH + "/" + provider + "/callback")
                        .session(authorization.session())
                        .param("code", AUTHORIZATION_CODE)
                        .param("state", authorization.state()))
                .andExpect(status().isFound())
                .andReturn().getResponse();
    }

    /*
        시작 → 제공자 인증 → 콜백. email·emailVerified는 제공자가 주는 값이다.
        GOOGLE은 ID 토큰에 그대로 담는다. KAKAO는 실제처럼 ID 토큰에 email_verified 없이 email만 담고, userinfo가 둘 다 준다
     */
    private MockHttpServletResponse socialLogin(String provider, String subName, String email, Object emailVerified)
            throws Exception {
        Authorization authorization = start(provider);
        if ("kakao".equals(provider)) {
            stubTokenResponse(sign(idTokenClaims(provider, authorization, subName, email, null), PROVIDER_KEY));
            stubUserInfo(userInfoClaims(sub(subName), email, emailVerified));
        } else {
            stubTokenResponse(sign(idTokenClaims(provider, authorization, subName, email, emailVerified), PROVIDER_KEY));
        }
        return callback(provider, authorization);
    }

    private MockHttpServletResponse socialLogin(String provider, String subName, String email) throws Exception {
        return socialLogin(provider, subName, email, true);
    }

    // 쿠키·CSRF

    private String issueCsrfToken() throws Exception {
        Cookie cookie = mockMvc.perform(get(CSRF_PATH)).andReturn().getResponse().getCookie(XSRF_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private ResultActions signup(String signupToken, String body) throws Exception {
        String csrf = issueCsrfToken();
        List<Cookie> cookies = new ArrayList<>(List.of(new Cookie(XSRF_COOKIE, csrf)));
        if (signupToken != null) {
            cookies.add(new Cookie(SIGNUP_COOKIE, signupToken));
        }
        return mockMvc.perform(post(SIGNUP_PATH)
                .cookie(cookies.toArray(Cookie[]::new))
                .header(XSRF_HEADER, csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String nicknameBody(String nickname) {
        return "{\"nickname\":\"" + nickname + "\"}";
    }

    private static Optional<String> setCookie(MockHttpServletResponse response, String name) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst();
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    // CSRF spa()는 XSRF-TOKEN 쿠키가 없는 모든 응답에 그 쿠키를 싣는다. 소셜 로그인이 발급하는 쿠키만 본다
    private static List<String> issuedCookies(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> !value.startsWith(XSRF_COOKIE + "="))
                .toList();
    }

    private static String signupToken(MockHttpServletResponse callback) {
        return cookieValue(setCookie(callback, SIGNUP_COOKIE).orElseThrow());
    }

    // 콜백 결과가 쿠키 없이 error=<code>로 끝났고, 가입 컨텍스트·Refresh 세션도 남지 않았다
    private void assertRejected(MockHttpServletResponse response, String code) {
        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND_CALLBACK + "?error=" + code);
        assertThat(issuedCookies(response)).isEmpty();
        assertThat(redisTemplate.keys("*")).isEmpty();
    }

    private ResultActions me(MockHttpServletResponse loginResponse) throws Exception {
        String accessToken = cookieValue(setCookie(loginResponse, "access_token").orElseThrow());
        return mockMvc.perform(get(ME_PATH).cookie(new Cookie("access_token", accessToken)));
    }

    // 1.4 시작

    @ParameterizedTest
    @ValueSource(strings = {"kakao", "google"})
    @DisplayName("시작은 302로 제공자 인증 화면에 보내고 state·nonce·PKCE(S256)·콜백 주소를 붙이며, 인가 요청은 세션에만 둔다")
    void startsAuthorization(String provider) throws Exception {
        // when
        MvcResult result = mockMvc.perform(get(BASE_PATH + "/" + provider))
                .andExpect(status().isFound())
                .andReturn();

        // then
        ClientRegistration registration = registration(provider);
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(registration.getProviderDetails().getAuthorizationUri() + "?");
        Authorization authorization = new Authorization(null,
                UriComponentsBuilder.fromUriString(location).build().getQueryParams());
        assertThat(authorization.param("response_type")).isEqualTo("code");
        assertThat(authorization.param("client_id")).isEqualTo(registration.getClientId());
        assertThat(authorization.param("scope")).isEqualTo(String.join(" ", registration.getScopes()));
        assertThat(authorization.param("redirect_uri"))
                .isEqualTo("http://localhost" + BASE_PATH + "/" + provider + "/callback");
        assertThat(authorization.state()).isNotBlank();
        assertThat(authorization.nonce()).isNotBlank();
        assertThat(authorization.param(PkceParameterNames.CODE_CHALLENGE)).isNotBlank();
        assertThat(authorization.param(PkceParameterNames.CODE_CHALLENGE_METHOD)).isEqualTo("S256");
        assertThat(location).doesNotContain(registration.getClientSecret());

        // 로그인 쿠키는 없고, 인가 요청을 담은 세션만 생긴다(MEMBER_AUTH 1.4)
        assertThat(setCookie(result.getResponse(), "access_token")).isEmpty();
        assertThat(setCookie(result.getResponse(), "refresh_token")).isEmpty();
        assertThat(result.getRequest().getSession(false)).isNotNull();
        verifyNoInteractions(tokenResponseClient);
    }

    @Test
    @DisplayName("지원하지 않는 제공자로 시작하면 400 UNSUPPORTED_OAUTH2_PROVIDER이고 세션이 생기지 않는다")
    void rejectsUnsupportedProvider() throws Exception {
        MvcResult result = mockMvc.perform(get(BASE_PATH + "/naver"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_OAUTH2_PROVIDER"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("POST /oauth2/signup은 인가 요청으로 가로채지지 않고 Controller에서 401 OAUTH2_SIGNUP_CONTEXT_INVALID로 응답한다")
    void signupIsNotInterceptedByAuthorizationFilter() throws Exception {
        signup(null, nicknameBody(nickname("a")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("OAUTH2_SIGNUP_CONTEXT_INVALID"));
        verifyNoInteractions(tokenResponseClient);
    }

    // 1.5 콜백: 최초 인증

    @Test
    @DisplayName("최초 인증은 회원을 만들지 않고 가입 컨텍스트와 oauth2_signup_token 쿠키를 발급해 signup_required로 보낸다")
    void issuesSignupContextOnFirstAuthentication() throws Exception {
        // given
        Authorization authorization = start("google");
        stubTokenResponse(sign(idTokenClaims("google", authorization, "first", email("first"), true), PROVIDER_KEY));

        // when
        MockHttpServletResponse response = callback("google", authorization);

        // then: 리다이렉트와 쿠키
        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND_CALLBACK + "?result=signup_required");
        assertThat(issuedCookies(response)).hasSize(1);
        String cookie = setCookie(response, SIGNUP_COOKIE).orElseThrow();
        assertThat(cookie).contains("Path=/api/v1/auth/oauth2/signup;", "Max-Age=900;", "HttpOnly", "Secure", "SameSite=Lax");
        assertThat(cookieValue(cookie)).hasSize(43);

        // then: 회원 미생성, 컨텍스트는 해시 키로 저장, 세션 무효화
        assertThat(countUsersBySub("first")).isZero();
        assertThat(countSignupContexts()).isEqualTo(1);
        assertThat(redisTemplate.keys("*" + cookieValue(cookie) + "*")).isEmpty();
        assertThat(authorization.session().isInvalid()).isTrue();

        // then: 인가 코드는 세션의 인가 요청(PKCE code_verifier 포함)과 함께 토큰 교환에 쓰였다
        verify(tokenResponseClient).getTokenResponse(org.mockito.ArgumentMatchers.argThat(request -> {
            OAuth2AuthorizationRequest authorizationRequest = request.getAuthorizationExchange().getAuthorizationRequest();
            return AUTHORIZATION_CODE.equals(request.getAuthorizationExchange().getAuthorizationResponse().getCode())
                    && authorizationRequest.getAttribute(PkceParameterNames.CODE_VERIFIER) != null;
        }));

        // then: GOOGLE은 userinfo 주소가 없어 ID 토큰만 쓴다
        verifyNoInteractions(userInfoService);
    }

    @Test
    @DisplayName("KAKAO는 ID 토큰에 email_verified가 없어도 OIDC userinfo의 email·email_verified로 가입 컨텍스트를 발급한다")
    void readsKakaoEmailFromUserInfo() throws Exception {
        // given: 실제 카카오처럼 ID 토큰에는 email만 있고 email_verified가 없다
        Authorization authorization = start("kakao");
        stubTokenResponse(sign(idTokenClaims("kakao", authorization, "userinfo", email("userinfo"), null), PROVIDER_KEY));
        stubUserInfo(userInfoClaims(sub("userinfo"), email("userinfo"), true));

        // when
        MockHttpServletResponse response = callback("kakao", authorization);

        // then
        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND_CALLBACK + "?result=signup_required");
        assertThat(setCookie(response, SIGNUP_COOKIE)).isPresent();
        assertThat(countSignupContexts()).isEqualTo(1);

        // then: userinfo는 토큰 교환에서 받은 access token으로 카카오 등록에 대해 호출했다
        verify(userInfoService).loadUser(org.mockito.ArgumentMatchers.argThat(request ->
                "kakao".equals(request.getClientRegistration().getRegistrationId())
                        && PROVIDER_ACCESS_TOKEN.equals(request.getAccessToken().getTokenValue())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"subject-mismatch", "request-failed"})
    @DisplayName("KAKAO userinfo의 sub가 ID 토큰과 다르거나 호출이 실패하면 OAUTH2_AUTHENTICATION_FAILED이고 회원·컨텍스트가 생기지 않는다")
    void rejectsInvalidKakaoUserInfo(String defect) throws Exception {
        // given
        Authorization authorization = start("kakao");
        stubTokenResponse(sign(idTokenClaims("kakao", authorization, "kuser", email("kuser"), null), PROVIDER_KEY));
        if ("subject-mismatch".equals(defect)) {
            stubUserInfo(userInfoClaims(sub("other"), email("kuser"), true));
        } else {
            when(userInfoService.loadUser(any()))
                    .thenThrow(new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info_response")));
        }

        // when & then
        assertRejected(callback("kakao", authorization), "OAUTH2_AUTHENTICATION_FAILED");
        assertThat(countUsersBySub("kuser")).isZero();
        assertThat(countUsersByEmail("kuser")).isZero();
    }

    @Test
    @DisplayName("제공자 이메일은 정규화해 가입 컨텍스트에 담고, 가입 완료 시 그 값으로 저장된다")
    void normalizesProviderEmail() throws Exception {
        // given
        MockHttpServletResponse callback = socialLogin("kakao", "upper", email("upper").toUpperCase());

        // when & then
        signup(signupToken(callback), nicknameBody(nickname("u")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.email").value(email("upper")));
        assertThat(countUsersByEmail("upper")).isEqualTo(1);
    }

    // 1.6 가입 완료

    @Test
    @DisplayName("가입 완료는 201과 쿠키 3개를 주고 USER/ACTIVE·password_hash 없음·제공자 식별자로 회원을 만들며 컨텍스트를 지운다")
    void completesSignup() throws Exception {
        // given
        MockHttpServletResponse callback = socialLogin("google", "signup", email("signup"));
        String signupToken = signupToken(callback);

        // when
        MockHttpServletResponse response = signup(signupToken, nicknameBody("  " + nickname("s") + " "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.user.userId").isNotEmpty())
                .andExpect(jsonPath("$.data.user.email").value(email("signup")))
                .andExpect(jsonPath("$.data.user.nickname").value(nickname("s")))
                .andExpect(jsonPath("$.data.user.roles.length()").value(1))
                .andExpect(jsonPath("$.data.user.roles[0]").value("USER"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn().getResponse();

        // then: 쿠키 3개(가입 쿠키 만료, 인증 쿠키 2개)
        assertThat(issuedCookies(response)).hasSize(3);
        assertThat(setCookie(response, SIGNUP_COOKIE).orElseThrow())
                .startsWith(SIGNUP_COOKIE + "=;")
                .contains("Path=/api/v1/auth/oauth2/signup;", "Max-Age=0;", "HttpOnly", "Secure", "SameSite=Lax");
        String access = setCookie(response, "access_token").orElseThrow();
        String refresh = setCookie(response, "refresh_token").orElseThrow();
        assertThat(access).contains("Path=/api;", "HttpOnly", "Secure", "SameSite=Lax");
        assertThat(refresh).contains("Path=/api/v1/auth;", "HttpOnly", "Secure", "SameSite=Lax");
        assertThat(response.getContentAsString())
                .doesNotContain(cookieValue(access))
                .doesNotContain(cookieValue(refresh))
                .doesNotContain(signupToken)
                .doesNotContain(sub("signup"));

        // then: DB
        Map<String, Object> user = jdbcTemplate.queryForMap("""
                SELECT role, status, provider, provider_user_id, password_hash, nickname FROM users WHERE email = ?
                """, email("signup"));
        assertThat(user)
                .containsEntry("role", "USER")
                .containsEntry("status", "ACTIVE")
                .containsEntry("provider", "GOOGLE")
                .containsEntry("provider_user_id", sub("signup"))
                .containsEntry("nickname", nickname("s"));
        assertThat(user.get("password_hash")).isNull();

        // then: 컨텍스트는 지워지고, 발급된 access_token으로 내 정보를 조회한다
        assertThat(countSignupContexts()).isZero();
        me(response)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email("signup")))
                .andExpect(jsonPath("$.data.provider").value("GOOGLE"));
    }

    @Test
    @DisplayName("가입 완료에 CSRF 토큰이 없으면 403 ACCESS_DENIED이고 회원 미생성·컨텍스트 유지")
    void rejectsSignupWithoutCsrf() throws Exception {
        // given
        String signupToken = signupToken(socialLogin("google", "csrf", email("csrf")));

        // when & then
        mockMvc.perform(post(SIGNUP_PATH)
                        .cookie(new Cookie(SIGNUP_COOKIE, signupToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nicknameBody(nickname("c"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        assertThat(countUsersBySub("csrf")).isZero();
        assertThat(countSignupContexts()).isEqualTo(1);
    }

    // 가입 컨텍스트

    @Test
    @DisplayName("이미 사용한 가입 쿠키로 다시 요청하면 401 OAUTH2_SIGNUP_CONTEXT_INVALID이고 회원이 더 생기지 않는다")
    void rejectsReusedSignupToken() throws Exception {
        // given
        String signupToken = signupToken(socialLogin("google", "reuse", email("reuse")));
        signup(signupToken, nicknameBody(nickname("r"))).andExpect(status().isCreated());

        // when & then
        signup(signupToken, nicknameBody(nickname("r2")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("OAUTH2_SIGNUP_CONTEXT_INVALID"));
        assertThat(countUsersBySub("reuse")).isEqualTo(1);
    }

    @Test
    @DisplayName("만료(키 소멸)·조작된 가입 쿠키는 잘못된 닉네임과 함께여도 401 OAUTH2_SIGNUP_CONTEXT_INVALID이고 회원 미생성")
    void rejectsExpiredOrTamperedSignupToken() throws Exception {
        // given
        String signupToken = signupToken(socialLogin("google", "expired", email("expired")));
        String tampered = signupToken.substring(0, 42) + (signupToken.endsWith("A") ? "B" : "A");

        // when & then: 조작
        signup(tampered, nicknameBody(nickname("t")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("OAUTH2_SIGNUP_CONTEXT_INVALID"));
        assertThat(countSignupContexts()).isEqualTo(1);

        // when & then: 만료. Redis TTL이 지나면 키가 사라지므로 키를 지워 같은 상태를 만든다
        redisTemplate.delete(redisTemplate.keys(SIGNUP_CONTEXT_KEYS));
        signup(signupToken, nicknameBody("!"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("OAUTH2_SIGNUP_CONTEXT_INVALID"));
        assertThat(countUsersBySub("expired")).isZero();
    }

    @Test
    @DisplayName("닉네임·요청 값 오류와 DUPLICATE_NICKNAME은 컨텍스트를 소비하지 않아 같은 쿠키로 다시 가입할 수 있다")
    void keepsContextOnNicknameErrors() throws Exception {
        // given
        insertLocalUser("tk");
        String signupToken = signupToken(socialLogin("kakao", "retry", email("retry")));

        // when & then: 각 오류 뒤에도 컨텍스트가 남는다
        signup(signupToken, nicknameBody("!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_NICKNAME"));
        signup(signupToken, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        signup(signupToken, "not-json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        signup(signupToken, nicknameBody(nickname("tk").toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_NICKNAME"));
        assertThat(countSignupContexts()).isEqualTo(1);
        assertThat(countUsersBySub("retry")).isZero();

        // then: 같은 쿠키로 가입된다
        signup(signupToken, nicknameBody(nickname("ok")))
                .andExpect(status().isCreated());
        assertThat(countUsersBySub("retry")).isEqualTo(1);
        assertThat(countSignupContexts()).isZero();
    }

    @Test
    @DisplayName("콜백 이후 같은 이메일 회원이 생기면 가입 완료는 409 DUPLICATE_EMAIL이고 컨텍스트를 소비한다")
    void rejectsSignupWhenEmailTakenAfterCallback() throws Exception {
        // given
        String signupToken = signupToken(socialLogin("google", "late", email("late")));
        insertLocalUser("late");

        // when & then
        signup(signupToken, nicknameBody(nickname("l")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"));
        assertThat(countUsersBySub("late")).isZero();
        assertThat(countSignupContexts()).isZero();
    }

    /*
        같은 가입 쿠키로 동시에 두 번 제출하면 회원은 하나만 생긴다(M00-06, M01-07b에서 넘긴 분기).
        나중 요청은 사전 검사 시점에 따라 401 OAUTH2_SIGNUP_CONTEXT_INVALID(이미 소비됨) 또는
        409 DUPLICATE_EMAIL(사전 검사를 함께 통과한 뒤 저장에서 uq_users_email 위반)이 된다.
        두 요청의 이메일이 같아 uq_users_provider_user보다 먼저 만든 uq_users_email 위반이 보고되므로 어느 응답일지는 정하지 않는다.
     */
    @Test
    @DisplayName("같은 가입 쿠키로 동시에 두 번 제출해도 회원은 하나만 생기고 나머지는 401 또는 409다")
    void createsOneUserOnConcurrentDoubleSubmit() throws Exception {
        // given
        String signupToken = signupToken(socialLogin("google", "double", email("double")));
        String csrf = issueCsrfToken();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        try {
            for (String suffix : List.of("d1", "d2")) {
                Callable<MockHttpServletResponse> task = () -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(post(SIGNUP_PATH)
                                    .cookie(new Cookie(XSRF_COOKIE, csrf), new Cookie(SIGNUP_COOKIE, signupToken))
                                    .header(XSRF_HEADER, csrf)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(nicknameBody(nickname(suffix))))
                            .andReturn().getResponse();
                };
                futures.add(executor.submit(task));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            // when
            List<Integer> statuses = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS).getStatus());
            }

            // then
            assertThat(statuses).containsOnlyOnce(201);
            assertThat(statuses).filteredOn(status -> status != 201).singleElement()
                    .isIn(401, 409);
            assertThat(countUsersBySub("double")).isEqualTo(1);
            assertThat(countSignupContexts()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    // 1.5 콜백: 재로그인·충돌·비활성

    @Test
    @DisplayName("가입한 소셜 회원이 다시 로그인하면 인증 쿠키 2개와 success를 받고 회원이 더 생기지 않으며, 제공자 이메일이 바뀌어도 같은 회원이다")
    void logsInExistingSocialUser() throws Exception {
        // given
        signup(signupToken(socialLogin("kakao", "again", email("again"))), nicknameBody(nickname("g")))
                .andExpect(status().isCreated());

        // when: 제공자 쪽 이메일이 바뀐 채 같은 식별자로 로그인
        MockHttpServletResponse response = socialLogin("kakao", "again", email("changed"));

        // then
        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND_CALLBACK + "?result=success");
        assertThat(issuedCookies(response)).hasSize(2);
        assertThat(setCookie(response, "access_token").orElseThrow())
                .contains("Path=/api;", "HttpOnly", "Secure", "SameSite=Lax");
        assertThat(setCookie(response, "refresh_token").orElseThrow())
                .contains("Path=/api/v1/auth;", "HttpOnly", "Secure", "SameSite=Lax");
        assertThat(setCookie(response, SIGNUP_COOKIE)).isEmpty();
        assertThat(countUsersBySub("again")).isEqualTo(1);
        assertThat(countUsersByEmail("changed")).isZero();
        assertThat(countSignupContexts()).isZero();
        me(response)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email("again")))
                .andExpect(jsonPath("$.data.provider").value("KAKAO"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCAL", "KAKAO", "GOOGLE"})
    @DisplayName("같은 이메일의 LOCAL·다른 제공자·같은 제공자의 다른 식별자 회원이 있으면 ACCOUNT_LINK_REQUIRED이고 기존 회원은 바뀌지 않는다")
    void rejectsEmailConflict(String existingProvider) throws Exception {
        // given: 이메일은 같고 GOOGLE 식별자는 다른 회원
        Map<String, Object> existing = "LOCAL".equals(existingProvider)
                ? insertLocalUser("owner")
                : insertSocialUser(existingProvider, "owner", "ACTIVE");
        Map<String, Object> before = userRow(existing.get("id"));

        // when
        MockHttpServletResponse response = socialLogin("google", "newcomer", email("owner"));

        // then
        assertRejected(response, "ACCOUNT_LINK_REQUIRED");
        assertThat(userRow(existing.get("id"))).isEqualTo(before);
        assertThat(countUsersBySub("newcomer")).isZero();
        assertThat(countUsersByEmail("owner")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "WITHDRAWN"})
    @DisplayName("비활성 소셜 회원은 ACCOUNT_DISABLED이고 쿠키·세션이 생기지 않으며 회원은 바뀌지 않는다")
    void rejectsDisabledSocialUser(String status) throws Exception {
        // given
        Map<String, Object> user = insertSocialUser("GOOGLE", "disabled", status);
        Map<String, Object> before = userRow(user.get("id"));

        // when
        MockHttpServletResponse response = socialLogin("google", "disabled", email("disabled"));

        // then
        assertRejected(response, "ACCOUNT_DISABLED");
        assertThat(userRow(user.get("id"))).isEqualTo(before);
    }

    @Test
    @DisplayName("제공자 이메일(KAKAO는 userinfo, GOOGLE은 ID 토큰)이 없거나 검증되지 않았으면 OAUTH2_EMAIL_REQUIRED이고 회원·컨텍스트가 생기지 않는다")
    void rejectsUnverifiedEmail() throws Exception {
        assertRejected(socialLogin("kakao", "noemail", null, null), "OAUTH2_EMAIL_REQUIRED");
        assertRejected(socialLogin("kakao", "unverified", email("unverified"), false), "OAUTH2_EMAIL_REQUIRED");
        assertRejected(socialLogin("kakao", "absent", email("absent"), null), "OAUTH2_EMAIL_REQUIRED");
        assertRejected(socialLogin("google", "unknown", email("unknown"), null), "OAUTH2_EMAIL_REQUIRED");
        assertThat(countUsersByEmail("unverified")).isZero();
        assertThat(countUsersByEmail("absent")).isZero();
    }

    // 1.5 콜백: 제공자 인증 실패

    @Test
    @DisplayName("state가 다르거나 이미 쓴 state면 토큰 교환 없이 OAUTH2_AUTHENTICATION_FAILED이고 세션을 무효화한다")
    void rejectsInvalidState() throws Exception {
        // given
        Authorization authorization = start("google");

        // when: 다른 state
        MockHttpServletResponse wrong = mockMvc.perform(get(BASE_PATH + "/google/callback")
                        .session(authorization.session())
                        .param("code", AUTHORIZATION_CODE)
                        .param("state", "forged-state"))
                .andExpect(status().isFound())
                .andReturn().getResponse();

        // then
        assertRejected(wrong, "OAUTH2_AUTHENTICATION_FAILED");
        assertThat(authorization.session().isInvalid()).isTrue();
        verifyNoInteractions(tokenResponseClient);

        // when: 성공한 콜백을 같은 state로 다시 호출(세션이 무효화돼 인가 요청이 없다)
        Authorization used = start("google");
        stubTokenResponse(sign(idTokenClaims("google", used, "replay", email("replay"), true), PROVIDER_KEY));
        callback("google", used);
        redisTemplate.delete(redisTemplate.keys(SIGNUP_CONTEXT_KEYS));
        MockHttpServletResponse replay = mockMvc.perform(get(BASE_PATH + "/google/callback")
                        .param("code", AUTHORIZATION_CODE)
                        .param("state", used.state()))
                .andExpect(status().isFound())
                .andReturn().getResponse();

        // then
        assertRejected(replay, "OAUTH2_AUTHENTICATION_FAILED");
    }

    @Test
    @DisplayName("사용자가 제공자 화면에서 취소하면(access_denied) 토큰 교환 없이 OAUTH2_AUTHENTICATION_FAILED")
    void rejectsCanceledAuthorization() throws Exception {
        // given
        Authorization authorization = start("kakao");

        // when
        MockHttpServletResponse response = mockMvc.perform(get(BASE_PATH + "/kakao/callback")
                        .session(authorization.session())
                        .param("error", "access_denied")
                        .param("error_description", "user canceled")
                        .param("state", authorization.state()))
                .andExpect(status().isFound())
                .andReturn().getResponse();

        // then
        assertRejected(response, "OAUTH2_AUTHENTICATION_FAILED");
        assertThat(authorization.session().isInvalid()).isTrue();
        verifyNoInteractions(tokenResponseClient);
    }

    @Test
    @DisplayName("인가 코드의 토큰 교환이 실패하면 OAUTH2_AUTHENTICATION_FAILED")
    void rejectsTokenExchangeFailure() throws Exception {
        // given
        Authorization authorization = start("google");
        when(tokenResponseClient.getTokenResponse(any()))
                .thenThrow(new OAuth2AuthorizationException(new OAuth2Error("invalid_grant")));

        // when & then
        assertRejected(callback("google", authorization), "OAUTH2_AUTHENTICATION_FAILED");
        assertThat(authorization.session().isInvalid()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "forged-signature", "wrong-audience", "wrong-issuer", "wrong-nonce", "expired"})
    @DisplayName("ID 토큰이 없거나 서명·aud·iss·nonce·만료 검증에 실패하면 OAUTH2_AUTHENTICATION_FAILED이고 회원·컨텍스트가 생기지 않는다")
    void rejectsInvalidIdToken(String defect) throws Exception {
        // given
        Authorization authorization = start("google");
        Map<String, Object> claims = idTokenClaims("google", authorization, "idtoken", email("idtoken"), true);
        RSAKey key = PROVIDER_KEY;
        switch (defect) {
            case "forged-signature" -> key = FORGED_KEY;
            case "wrong-audience" -> claims.put("aud", "other-client-id");
            case "wrong-issuer" -> claims.put("iss", "https://issuer.example.com");
            case "wrong-nonce" -> claims.put("nonce", "other-nonce");
            case "expired" -> {
                claims.put("iat", Instant.now().minusSeconds(3600).getEpochSecond());
                claims.put("exp", Instant.now().minusSeconds(1800).getEpochSecond());
            }
            default -> {
            }
        }
        stubTokenResponse("missing".equals(defect) ? null : sign(claims, key));

        // when & then
        assertRejected(callback("google", authorization), "OAUTH2_AUTHENTICATION_FAILED");
        assertThat(countUsersBySub("idtoken")).isZero();
    }

    @Test
    @DisplayName("지원하지 않는 제공자의 콜백은 OAUTH2_AUTHENTICATION_FAILED로 보낸다")
    void rejectsUnsupportedProviderCallback() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get(BASE_PATH + "/naver/callback")
                        .param("code", AUTHORIZATION_CODE)
                        .param("state", "state"))
                .andExpect(status().isFound())
                .andReturn().getResponse();
        assertRejected(response, "OAUTH2_AUTHENTICATION_FAILED");
        verifyNoInteractions(tokenResponseClient);
    }

    // 1.5 콜백: 서버 오류(GR-46 M01-05에서 넘긴 분기)

    @Test
    @DisplayName("가입 컨텍스트 저장(Redis)이 실패하면 쿠키 없이 INTERNAL_SERVER_ERROR로 보낸다")
    void redirectsInternalErrorWhenSignupContextSaveFails() throws Exception {
        // given
        doThrow(new RedisConnectionFailureException("redis down")).when(signupContextRepository).save(any(), any());

        // when & then
        assertRejected(socialLogin("google", "redis", email("redis")), "INTERNAL_SERVER_ERROR");
        assertThat(countUsersBySub("redis")).isZero();
    }

    @Test
    @DisplayName("기존 회원의 Refresh 세션 저장(Redis)이 실패하면 인증 쿠키 없이 INTERNAL_SERVER_ERROR로 보낸다")
    void redirectsInternalErrorWhenRefreshSessionSaveFails() throws Exception {
        // given
        insertSocialUser("KAKAO", "session", "ACTIVE");
        doThrow(new RedisConnectionFailureException("redis down")).when(refreshTokenRepository).save(any(), any());

        // when & then
        assertRejected(socialLogin("kakao", "session", email("session")), "INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("회원 조회(DB)가 실패하면 쿠키 없이 INTERNAL_SERVER_ERROR로 보낸다")
    void redirectsInternalErrorWhenDatabaseFails() throws Exception {
        // given
        doThrow(new DataAccessResourceFailureException("db down")).when(socialAccountService).findLoginUser(any(), any());

        // when & then
        assertRejected(socialLogin("google", "db", email("db")), "INTERNAL_SERVER_ERROR");
    }

    // 민감 정보 노출(NFR-010)

    @Test
    @DisplayName("가입·로그인·실패의 리다이렉트 URL과 로그에 인가 코드·토큰·이메일·제공자 식별자·Client Secret이 없다")
    void doesNotExposeSensitiveValues(CapturedOutput output) throws Exception {
        // 클래스 단위 캡처에는 앞선 테스트의 출력(실패 시 MockMvc 요청 덤프 포함)도 쌓이므로 이 테스트의 출력만 본다
        int offset = output.getAll().length();

        // given & when: 최초 인증 → 가입 완료 → 재로그인 → 이메일 충돌
        Authorization authorization = start("google");
        String idToken = sign(idTokenClaims("google", authorization, "secret", email("secret"), true), PROVIDER_KEY);
        stubTokenResponse(idToken);
        MockHttpServletResponse first = callback("google", authorization);
        String signupToken = signupToken(first);
        MockHttpServletResponse signup = signup(signupToken, nicknameBody(nickname("x")))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        MockHttpServletResponse again = socialLogin("google", "secret", email("secret"));
        MockHttpServletResponse conflict = socialLogin("kakao", "other", email("secret"));

        // then: 리다이렉트 URL은 결과·오류 코드뿐이다
        assertThat(first.getRedirectedUrl()).isEqualTo(FRONTEND_CALLBACK + "?result=signup_required");
        assertThat(again.getRedirectedUrl()).isEqualTo(FRONTEND_CALLBACK + "?result=success");
        assertThat(conflict.getRedirectedUrl()).isEqualTo(FRONTEND_CALLBACK + "?error=ACCOUNT_LINK_REQUIRED");

        // then: 로그
        List<String> secrets = List.of(
                AUTHORIZATION_CODE, PROVIDER_ACCESS_TOKEN, idToken, signupToken,
                GOOGLE_CLIENT_SECRET, KAKAO_CLIENT_SECRET,
                email("secret"), sub("secret"), sub("other"), authorization.state(),
                cookieValue(setCookie(signup, "access_token").orElseThrow()),
                cookieValue(setCookie(signup, "refresh_token").orElseThrow()),
                cookieValue(setCookie(again, "access_token").orElseThrow()),
                cookieValue(setCookie(again, "refresh_token").orElseThrow()));
        String logs = output.getAll().substring(offset);
        assertThat(logs).contains("소셜 로그인 성공", "소셜 로그인 거부");
        for (String secret : secrets) {
            assertThat(logs).doesNotContain(secret);
        }
    }
}
