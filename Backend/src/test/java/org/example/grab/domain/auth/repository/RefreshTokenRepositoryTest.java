package org.example.grab.domain.auth.repository;

import org.example.grab.domain.auth.support.RefreshTokenHasher;
import org.example.grab.domain.auth.support.RefreshTokenTtlCalculator;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

// GR-33 M03-01: 저장소 계약. 실제 Redis 동작(TTL·만료)은 M04 통합 테스트에서 확인한다
@ExtendWith(MockitoExtension.class)
class RefreshTokenRepositoryTest {

    private static final String RAW_TOKEN = "Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo";
    private static final String KEY = RefreshTokenHasher.toKey(RAW_TOKEN);
    private static final RefreshTokenSession SESSION = new RefreshTokenSession(
            7L, UUID.fromString("3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f"), Instant.parse("2026-10-31T06:00:00Z"));
    private static final String SESSION_JSON =
            "{\"userId\":7,\"sessionId\":\"3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f\",\"sessionExpiresAt\":\"2026-10-31T06:00:00Z\"}";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private RefreshTokenTtlCalculator ttlCalculator;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private RefreshTokenRepository repository;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        repository = new RefreshTokenRepository(redisTemplate, objectMapper, ttlCalculator);
    }

    @Test
    @DisplayName("save는 해시 키에 세션 JSON을 계산한 TTL로 저장하고 그 TTL을 돌려준다")
    void saveStoresSessionUnderHashedKeyWithTtl() {
        // given
        given(ttlCalculator.calculateTtl(SESSION.sessionExpiresAt())).willReturn(Duration.ofDays(14));
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);

        // when
        Duration ttl = repository.save(RAW_TOKEN, SESSION);

        // then
        assertThat(ttl).isEqualTo(Duration.ofDays(14));
        then(valueOperations).should().set(eq(KEY), value.capture(), eq(Duration.ofDays(14)));
        assertThat(value.getValue()).doesNotContain(RAW_TOKEN);
        assertThat(objectMapper.readValue(value.getValue(), RefreshTokenSession.class)).isEqualTo(SESSION);
    }

    @Test
    @DisplayName("save는 sessionExpiresAt을 ISO-8601 UTC 문자열로 저장하고 클래스 이름을 넣지 않는다")
    void saveWritesPlainJsonWithIsoInstant() {
        // given
        given(ttlCalculator.calculateTtl(any())).willReturn(Duration.ofDays(14));
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);

        // when
        repository.save(RAW_TOKEN, SESSION);

        // then
        then(valueOperations).should().set(anyString(), value.capture(), any(Duration.class));
        assertThat(objectMapper.readTree(value.getValue())).isEqualTo(objectMapper.readTree(SESSION_JSON));
    }

    @Test
    @DisplayName("절대 만료가 지난 세션은 저장하지 않고 INVALID_TOKEN 예외가 발생한다")
    void saveRejectsExpiredSession() {
        // given
        given(ttlCalculator.calculateTtl(any())).willThrow(new BusinessException(CommonErrorCode.INVALID_TOKEN));

        // when & then
        assertThatThrownBy(() -> repository.save(RAW_TOKEN, SESSION))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_TOKEN));
        then(valueOperations).should(never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("find는 해시 키의 값을 세션으로 돌려준다")
    void findReturnsStoredSession() {
        // given
        given(valueOperations.get(KEY)).willReturn(SESSION_JSON);

        // when & then
        assertThat(repository.find(RAW_TOKEN)).contains(SESSION);
    }

    @Test
    @DisplayName("키가 없으면 find는 빈 결과다")
    void findReturnsEmptyWhenMissing() {
        // given
        given(valueOperations.get(KEY)).willReturn(null);

        // when & then
        assertThat(repository.find(RAW_TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("모르는 필드가 있어도 find는 세션을 읽는다")
    void findIgnoresUnknownFields() {
        // given
        String withUnknownField = SESSION_JSON.replace("}", ",\"deviceName\":\"iPhone\"}");
        given(valueOperations.get(KEY)).willReturn(withUnknownField);

        // when & then
        assertThat(repository.find(RAW_TOKEN)).contains(SESSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json",
            "null",
            "{\"userId\":7,\"sessionId\":\"3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f\"}",
            "{\"userId\":7,\"sessionId\":\"not-uuid\",\"sessionExpiresAt\":\"2026-10-31T06:00:00Z\"}"
    })
    @DisplayName("읽을 수 없거나 필드가 빠진 값이면 find는 예외 없이 빈 결과다")
    void findReturnsEmptyForUnreadableValue(String value) {
        // given
        given(valueOperations.get(KEY)).willReturn(value);

        // when & then
        assertThat(repository.find(RAW_TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("delete는 해시 키를 지운다")
    void deleteRemovesHashedKey() {
        // when
        repository.delete(RAW_TOKEN);

        // then
        then(redisTemplate).should().delete(KEY);
    }
}
