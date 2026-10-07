package org.example.grab.domain.user.repository;

import org.example.grab.domain.user.support.EmailVerificationHasher;
import org.example.grab.domain.user.support.EmailVerificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// 실제 Redis에서 나오지 않는 응답(INCR 결과 null)만 확인한다. Redis 동작은 EmailVerificationCodeRepositoryIntegrationTest에서 확인한다
@ExtendWith(MockitoExtension.class)
class EmailVerificationCodeRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private EmailVerificationCodeRepository repository;

    @BeforeEach
    void setUp() {
        String secret = Base64.getEncoder()
                .encodeToString("grab-test-only-email-verification-secret".getBytes(StandardCharsets.UTF_8));
        EmailVerificationHasher hasher = new EmailVerificationHasher(new EmailVerificationProperties(secret));
        repository = new EmailVerificationCodeRepository(redisTemplate, hasher);
    }

    @Test
    @DisplayName("INCR 결과가 없으면 0으로 바꾸지 않고 예외를 던져 코드 비교가 허용되지 않게 한다")
    void incrementAttemptsFailsWhenIncrementResultIsMissing() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.increment(anyString())).willReturn(null);

        // when & then
        assertThatThrownBy(() -> repository.incrementAttempts("user@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("user@example.com");
        then(redisTemplate).should(never()).expire(anyString(), any(Duration.class));
    }
}
