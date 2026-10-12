package org.example.grab.domain.user.service;

import org.example.grab.domain.user.dto.request.SignupRequest;
import org.example.grab.domain.user.dto.response.SignupResponse;
import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserRole;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.EmailSignupContextRepository;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.error.BusinessException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

// GR-29 M03-01·M03-02: 가입 흐름의 순서와 분기, 저장 시 제약 위반 변환, 컨텍스트 소비 여부. 저장소·인코더는 mock이다
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class SignupServiceTest {

    private static final String TOKEN = "Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo";
    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "Password123!";
    private static final String NICKNAME = "드롭헌터";
    private static final String PASSWORD_HASH = "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";
    private static final SignupRequest REQUEST = new SignupRequest(PASSWORD, NICKNAME);

    @Mock
    private EmailSignupContextRepository signupContextRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private SignupService service;

    @BeforeEach
    void setUp() {
        service = new SignupService(signupContextRepository, userRepository, passwordEncoder);
    }

    private void givenSignupPossible() {
        given(signupContextRepository.findEmail(TOKEN)).willReturn(Optional.of(EMAIL));
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(userRepository.existsByNicknameIgnoreCase(NICKNAME)).willReturn(false);
        given(passwordEncoder.encode(PASSWORD)).willReturn(PASSWORD_HASH);
        given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    // 사전 검사는 통과했지만 저장에서 실패하는 동시 가입 상황
    private void givenSaveFails(RuntimeException failure) {
        given(signupContextRepository.findEmail(TOKEN)).willReturn(Optional.of(EMAIL));
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(userRepository.existsByNicknameIgnoreCase(NICKNAME)).willReturn(false);
        given(passwordEncoder.encode(PASSWORD)).willReturn(PASSWORD_HASH);
        given(userRepository.save(any(User.class))).willThrow(failure);
    }

    // Spring이 Hibernate 예외를 감싼 형태. PostgreSQL 오류 문구에는 입력값이 들어간다
    private static DataIntegrityViolationException violation(String constraintName) {
        SQLException sqlException = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"" + constraintName + "\" Detail: Key (email)=(" + EMAIL + ")",
                "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sqlException, constraintName));
    }

    @Test
    @DisplayName("가입하면 컨텍스트의 이메일·해시한 비밀번호로 회원을 저장하고, 저장 뒤 컨텍스트를 소비해 응답을 돌려준다")
    void signsUpWithContextEmail() {
        // given
        givenSignupPossible();

        // when
        SignupResponse response = service.signup(TOKEN, REQUEST);

        // then
        InOrder order = inOrder(signupContextRepository, userRepository, passwordEncoder);
        order.verify(signupContextRepository).findEmail(TOKEN);
        order.verify(userRepository).existsByEmail(EMAIL);
        order.verify(userRepository).existsByNicknameIgnoreCase(NICKNAME);
        // 비밀번호는 가공 없이 그대로 인코더에 넘긴다
        order.verify(passwordEncoder).encode(PASSWORD);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        order.verify(userRepository).save(saved.capture());
        order.verify(signupContextRepository).delete(TOKEN);

        User user = saved.getValue();
        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.getPasswordHash()).isEqualTo(PASSWORD_HASH);
        assertThat(user.getNickname()).isEqualTo(NICKNAME);
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getProvider()).isEqualTo(AuthProvider.LOCAL);

        assertThat(response.userId()).isEqualTo(user.getUuid());
        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.nickname()).isEqualTo(NICKNAME);
        assertThat(response.roles()).containsExactly("USER");
        assertThat(response.createdAt()).isEqualTo(user.getCreatedAt());
    }

    @Test
    @DisplayName("컨텍스트가 없으면 EMAIL_SIGNUP_CONTEXT_INVALID이고 회원 조회·해시·저장·소비를 하지 않는다")
    void rejectsMissingContext() {
        // given
        given(signupContextRepository.findEmail(TOKEN)).willReturn(Optional.empty());

        // when & then
        assertErrorCode(UserErrorCode.EMAIL_SIGNUP_CONTEXT_INVALID);
        then(userRepository).shouldHaveNoInteractions();
        then(passwordEncoder).shouldHaveNoInteractions();
        then(signupContextRepository).should(never()).delete(anyString());
    }

    @Test
    @DisplayName("컨텍스트 조회가 Redis 오류로 실패하면 감싸지 않고 전파한다")
    void propagatesContextLookupFailure() {
        // given
        RedisConnectionFailureException failure = new RedisConnectionFailureException("connection refused");
        given(signupContextRepository.findEmail(TOKEN)).willThrow(failure);

        // when & then
        assertThatThrownBy(() -> service.signup(TOKEN, REQUEST)).isSameAs(failure);
        then(userRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 컨텍스트를 소비하고 DUPLICATE_EMAIL이며, 닉네임 검사·해시·저장을 하지 않는다")
    void rejectsDuplicateEmailAndConsumesContext() {
        // given: 닉네임도 중복이어도 이메일이 먼저다(M00-03)
        given(signupContextRepository.findEmail(TOKEN)).willReturn(Optional.of(EMAIL));
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);

        // when & then
        assertErrorCode(UserErrorCode.DUPLICATE_EMAIL);
        then(signupContextRepository).should().delete(TOKEN);
        then(userRepository).should(never()).existsByNicknameIgnoreCase(anyString());
        then(passwordEncoder).shouldHaveNoInteractions();
        then(userRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("닉네임이 중복이면 DUPLICATE_NICKNAME이고 컨텍스트를 소비하지 않으며 해시·저장을 하지 않는다")
    void rejectsDuplicateNicknameWithoutConsumingContext() {
        // given
        given(signupContextRepository.findEmail(TOKEN)).willReturn(Optional.of(EMAIL));
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(userRepository.existsByNicknameIgnoreCase(NICKNAME)).willReturn(true);

        // when & then
        assertErrorCode(UserErrorCode.DUPLICATE_NICKNAME);
        then(signupContextRepository).should(never()).delete(anyString());
        then(passwordEncoder).shouldHaveNoInteractions();
        then(userRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("저장 뒤 컨텍스트 소비가 실패해도 가입 응답을 그대로 돌려주고, WARN 로그에 토큰·비밀번호가 없다")
    void returnsResponseWhenConsumingContextFails(CapturedOutput output) {
        // given
        givenSignupPossible();
        willThrow(new RedisConnectionFailureException("connection refused")).given(signupContextRepository).delete(TOKEN);

        // when
        SignupResponse response = service.signup(TOKEN, REQUEST);

        // then
        assertThat(response.email()).isEqualTo(EMAIL);
        then(userRepository).should().save(any(User.class));
        assertThat(output).contains("가입 컨텍스트 소비 실패: RedisConnectionFailureException")
                .doesNotContain(TOKEN)
                .doesNotContain(PASSWORD)
                .doesNotContain(PASSWORD_HASH);
    }

    @Test
    @DisplayName("이메일 중복에서 컨텍스트 소비가 실패해도 응답은 DUPLICATE_EMAIL이다")
    void keepsDuplicateEmailWhenConsumingContextFails() {
        // given
        given(signupContextRepository.findEmail(TOKEN)).willReturn(Optional.of(EMAIL));
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);
        willThrow(new RedisConnectionFailureException("connection refused")).given(signupContextRepository).delete(TOKEN);

        // when & then
        assertErrorCode(UserErrorCode.DUPLICATE_EMAIL);
    }

    // 예외 메시지는 WARN 로그에 남으므로 토큰·이메일·비밀번호가 없어야 한다(M03-04)
    private void assertErrorCode(UserErrorCode errorCode) {
        assertThatThrownBy(() -> service.signup(TOKEN, REQUEST))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode))
                .hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining(EMAIL)
                .hasMessageNotContaining(PASSWORD);
    }

    @Test
    @DisplayName("동시 가입으로 저장 시 이메일 유니크 제약에 걸리면 컨텍스트를 소비하고 DUPLICATE_EMAIL이다")
    void convertsEmailUniqueViolation() {
        // given
        givenSaveFails(violation("uq_users_email"));

        // when & then
        assertErrorCode(UserErrorCode.DUPLICATE_EMAIL);
        then(signupContextRepository).should().delete(TOKEN);
    }

    @Test
    @DisplayName("동시 가입으로 저장 시 닉네임 유니크 인덱스에 걸리면 DUPLICATE_NICKNAME이고 컨텍스트를 소비하지 않는다")
    void convertsNicknameUniqueViolation() {
        // given
        givenSaveFails(violation("uq_users_nickname_lower"));

        // when & then
        assertErrorCode(UserErrorCode.DUPLICATE_NICKNAME);
        then(signupContextRepository).should(never()).delete(anyString());
    }

    @Test
    @DisplayName("다른 제약 위반은 중복으로 바꾸지 않고 그대로 던지며 컨텍스트를 소비하지 않는다")
    void rethrowsOtherConstraintViolation() {
        // given
        DataIntegrityViolationException failure = violation("ck_users_local_password");
        givenSaveFails(failure);

        // when & then
        assertThatThrownBy(() -> service.signup(TOKEN, REQUEST)).isSameAs(failure);
        then(signupContextRepository).should(never()).delete(anyString());
    }

    @Test
    @DisplayName("제약 이름을 알 수 없는 무결성 위반도 그대로 던지며 컨텍스트를 소비하지 않는다")
    void rethrowsViolationWithoutConstraintName() {
        // given
        DataIntegrityViolationException failure = new DataIntegrityViolationException("could not execute statement");
        givenSaveFails(failure);

        // when & then
        assertThatThrownBy(() -> service.signup(TOKEN, REQUEST)).isSameAs(failure);
        then(signupContextRepository).should(never()).delete(anyString());
    }
}
