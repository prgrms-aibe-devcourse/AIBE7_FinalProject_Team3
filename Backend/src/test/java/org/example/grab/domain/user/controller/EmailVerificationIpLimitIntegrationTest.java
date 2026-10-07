package org.example.grab.domain.user.controller;

import org.example.grab.domain.mail.service.AsyncEmailDispatcher;
import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
    GR-61 M04-03: 접속 IP(request.getRemoteAddr())가 실제 IP 한도(application.yml 1시간 30회)에 쓰이는지
    Controller → 서비스 → Redis 전체 경로로 확인한다. IP는 MockMvc의 setRemoteAddr로 바꾼다(M00-04).
    메일 발송만 MockitoBean으로 바꾸고, Redis는 Testcontainers, DB는 공통 PostgreSQL 컨테이너를 쓴다.
    Tomcat의 X-Forwarded-For 처리(native)는 MockMvc가 Tomcat을 거치지 않아 여기서 확인하지 않는다(M00-04 결정).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers
class EmailVerificationIpLimitIntegrationTest {

    private static final String REQUEST_PATH = "/api/v1/auth/email-verification";
    private static final String IP = "203.0.113.7";
    private static final String OTHER_IP = "198.51.100.1";
    private static final int IP_MAX_REQUESTS = 30;

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
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private AsyncEmailDispatcher emailDispatcher;

    @AfterEach
    void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    // 이메일마다 재발송 간격·이메일 한도가 따로라, 이메일을 바꿔 보내면 IP 한도만 쌓인다
    private ResultActions requestCode(int emailIndex, String remoteAddr, String forwardedFor) throws Exception {
        return mockMvc.perform(post(REQUEST_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user" + emailIndex + "@example.com\"}")
                .header("X-Forwarded-For", forwardedFor)
                .with(request -> {
                    request.setRemoteAddr(remoteAddr);
                    return request;
                }));
    }

    @Test
    @DisplayName("같은 IP는 이메일을 바꿔도 30회까지 204, 31회째 429이고 다른 IP는 영향이 없다")
    void limitsRequestsPerRemoteAddress() throws Exception {
        // given: 한도까지 요청한다
        for (int i = 1; i <= IP_MAX_REQUESTS; i++) {
            requestCode(i, IP, IP).andExpect(status().isNoContent());
        }

        // when & then
        requestCode(IP_MAX_REQUESTS + 1, IP, IP)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_RESEND_TOO_SOON"));
        requestCode(IP_MAX_REQUESTS + 2, OTHER_IP, OTHER_IP).andExpect(status().isNoContent());
        then(emailDispatcher).should(times(IP_MAX_REQUESTS + 1)).dispatch(any());
    }

    @Test
    @DisplayName("X-Forwarded-For 헤더를 요청마다 바꿔 보내도 접속 IP 기준으로 세어 한도를 피할 수 없다")
    void ignoresForwardedForHeader() throws Exception {
        // given: 헤더 값을 매번 다르게 위조한다
        for (int i = 1; i <= IP_MAX_REQUESTS; i++) {
            requestCode(i, IP, "10.0.0." + i).andExpect(status().isNoContent());
        }

        // when & then
        requestCode(IP_MAX_REQUESTS + 1, IP, "10.0.0.250")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_RESEND_TOO_SOON"));
    }
}
