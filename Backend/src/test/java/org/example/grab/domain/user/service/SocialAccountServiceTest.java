package org.example.grab.domain.user.service;

import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserRole;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.OAuth2SignupContext;
import org.example.grab.domain.user.repository.OAuth2SignupContextRepository;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.domain.user.support.SignupTokenGenerator;
import org.example.grab.global.error.BusinessException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

// GR-46 M02-01(M00-05·06): 콜백 판정의 회원 조회·이메일 충돌·가입 컨텍스트 발급, 가입 완료의 분기·제약 위반 변환·컨텍스트 소비. 저장소는 mock이다
@ExtendWith(MockitoExtension.class)
class SocialAccountServiceTest {

    private static final String SUB = "4021973658";
    private static final String EMAIL = "user@example.com";
    private static final String NICKNAME = "드롭헌터";
    private static final String TOKEN = "Qp7_mR2bW9xN4cT1vY8zL3kH6sD0fJ5gA2eU7iO4nBt";
    private static final OAuth2SignupContext CONTEXT = new OAuth2SignupContext(AuthProvider.KAKAO, SUB, EMAIL);

    @Mock
    private UserRepository userRepository;
    @Mock
    private OAuth2SignupContextRepository signupContextRepository;
    @Mock
    private SignupTokenGenerator signupTokenGenerator;

    private SocialAccountService service;

    @BeforeEach
    void setUp() {
        service = new SocialAccountService(userRepository, signupContextRepository, signupTokenGenerator);
    }

    private static User socialUser(UserStatus status) {
        User user = User.createSocial(EMAIL, AuthProvider.KAKAO, SUB, NICKNAME);
        ReflectionTestUtils.setField(user, "id", 7L);
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }

    private void givenSignupPossible() {
        given(signupContextRepository.find(TOKEN)).willReturn(Optional.of(CONTEXT));
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(userRepository.existsByNicknameIgnoreCase(NICKNAME)).willReturn(false);
    }

    // Spring이 Hibernate 예외를 감싼 형태. PostgreSQL 오류 문구에는 입력값이 들어간다(SignupServiceTest와 같음)
    private static DataIntegrityViolationException violation(String constraintName) {
        SQLException sqlException = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"" + constraintName + "\" Detail: Key (email)=(" + EMAIL + ")",
                "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sqlException, constraintName));
    }

    private static void assertErrorCode(Throwable thrown, UserErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    // 콜백 판정 1

    @Test
    @DisplayName("제공자 식별자로 ACTIVE 회원을 찾으면 로그인용 회원 정보를 돌려준다")
    void findsActiveMemberByProviderIdentifier() {
        // given
        given(userRepository.findByProviderAndProviderUserId(AuthProvider.KAKAO, SUB))
                .willReturn(Optional.of(socialUser(UserStatus.ACTIVE)));

        // when
        Optional<LoginUser> user = service.findLoginUser(AuthProvider.KAKAO, SUB);

        // then
        assertThat(user).hasValueSatisfying(u -> {
            assertThat(u.id()).isEqualTo(7L);
            assertThat(u.email()).isEqualTo(EMAIL);
            assertThat(u.role()).isEqualTo(UserRole.USER);
        });
    }

    @Test
    @DisplayName("제공자 식별자로 찾은 회원이 없으면 비어 있다")
    void returnsEmptyWhenNoMember() {
        // given
        given(userRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, SUB)).willReturn(Optional.empty());

        // when & then
        assertThat(service.findLoginUser(AuthProvider.GOOGLE, SUB)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    @DisplayName("제공자 식별자로 찾은 회원이 ACTIVE가 아니면 ACCOUNT_DISABLED다")
    void rejectsDisabledMember(UserStatus status) {
        // given
        given(userRepository.findByProviderAndProviderUserId(AuthProvider.KAKAO, SUB))
                .willReturn(Optional.of(socialUser(status)));

        // when & then
        assertErrorCode(catchThrowable(
                () -> service.findLoginUser(AuthProvider.KAKAO, SUB)), UserErrorCode.ACCOUNT_DISABLED);
    }

    // 콜백 판정 2·3

    @Test
    @DisplayName("같은 이메일 회원이 있으면 가입 방식과 관계없이 ACCOUNT_LINK_REQUIRED다")
    void rejectsTakenEmail() {
        // given
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);

        // when & then
        assertErrorCode(catchThrowable(
                () -> service.requireEmailNotTaken(EMAIL)), UserErrorCode.ACCOUNT_LINK_REQUIRED);
    }

    @Test
    @DisplayName("가입 컨텍스트 발급은 회원을 만들지 않고 프로필 값을 새 토큰으로 저장한 뒤 토큰 원문을 돌려준다")
    void issuesSignupContextWithoutCreatingMember() {
        // given
        given(signupTokenGenerator.generate()).willReturn(TOKEN);

        // when
        String token = service.issueSignupContext(AuthProvider.KAKAO, SUB, EMAIL);

        // then
        assertThat(token).isEqualTo(TOKEN);
        then(signupContextRepository).should().save(TOKEN, CONTEXT);
        then(userRepository).shouldHaveNoInteractions();
    }

    // 가입 완료

    @Test
    @DisplayName("가입 컨텍스트가 없으면 확인·가입 모두 OAUTH2_SIGNUP_CONTEXT_INVALID이고 회원을 만들지 않는다")
    void rejectsMissingContext() {
        // given
        given(signupContextRepository.find(TOKEN)).willReturn(Optional.empty());

        // when & then
        assertErrorCode(catchThrowable(
                () -> service.requireSignupContext(TOKEN)), UserErrorCode.OAUTH2_SIGNUP_CONTEXT_INVALID);
        assertErrorCode(catchThrowable(
                () -> service.signup(TOKEN, NICKNAME)), UserErrorCode.OAUTH2_SIGNUP_CONTEXT_INVALID);
        then(userRepository).shouldHaveNoInteractions();
        then(signupContextRepository).should(never()).delete(anyString());
    }

    @Test
    @DisplayName("가입하면 컨텍스트의 제공자·식별자·이메일로 USER·ACTIVE 소셜 회원을 저장하고, 저장 뒤 컨텍스트를 소비한다")
    void signsUpWithContextValues() {
        // given
        givenSignupPossible();
        given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);

        // when
        LoginUser user = service.signup(TOKEN, NICKNAME);

        // then
        InOrder order = inOrder(userRepository, signupContextRepository);
        order.verify(userRepository).save(saved.capture());
        order.verify(signupContextRepository).delete(TOKEN);
        User member = saved.getValue();
        assertThat(member.getEmail()).isEqualTo(EMAIL);
        assertThat(member.getProvider()).isEqualTo(AuthProvider.KAKAO);
        assertThat(member.getProviderUserId()).isEqualTo(SUB);
        assertThat(member.getNickname()).isEqualTo(NICKNAME);
        assertThat(member.getPasswordHash()).isNull();
        assertThat(member.getRole()).isEqualTo(UserRole.USER);
        assertThat(member.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.email()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("이메일 중복은 닉네임보다 먼저 보고, 컨텍스트를 소비한 뒤 DUPLICATE_EMAIL이다")
    void consumesContextOnDuplicateEmail() {
        // given
        given(signupContextRepository.find(TOKEN)).willReturn(Optional.of(CONTEXT));
        given(userRepository.existsByEmail(EMAIL)).willReturn(true);

        // when & then
        assertErrorCode(catchThrowable(
                () -> service.signup(TOKEN, NICKNAME)), UserErrorCode.DUPLICATE_EMAIL);
        then(signupContextRepository).should().delete(TOKEN);
        then(userRepository).should(never()).existsByNicknameIgnoreCase(anyString());
        then(userRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("닉네임 중복은 컨텍스트를 소비하지 않아 같은 쿠키로 다시 요청할 수 있다")
    void keepsContextOnDuplicateNickname() {
        // given
        given(signupContextRepository.find(TOKEN)).willReturn(Optional.of(CONTEXT));
        given(userRepository.existsByEmail(EMAIL)).willReturn(false);
        given(userRepository.existsByNicknameIgnoreCase(NICKNAME)).willReturn(true);

        // when & then
        assertErrorCode(catchThrowable(
                () -> service.signup(TOKEN, NICKNAME)), UserErrorCode.DUPLICATE_NICKNAME);
        then(signupContextRepository).should(never()).delete(anyString());
        then(userRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("저장 중 uq_users_email 위반은 컨텍스트를 소비하고 DUPLICATE_EMAIL이며, 원인 예외(SQL·입력값)를 연결하지 않는다")
    void translatesEmailConstraintViolation() {
        // given
        givenSignupPossible();
        given(userRepository.save(any(User.class))).willThrow(violation("uq_users_email"));

        // when
        Throwable thrown = catchThrowable(() -> service.signup(TOKEN, NICKNAME));

        // then
        assertErrorCode(thrown, UserErrorCode.DUPLICATE_EMAIL);
        assertThat(thrown.getCause()).isNull();
        assertThat(thrown.getMessage()).doesNotContain(EMAIL);
        then(signupContextRepository).should().delete(TOKEN);
    }

    @Test
    @DisplayName("저장 중 uq_users_provider_user 위반(같은 컨텍스트 이중 제출)은 컨텍스트를 소비하고 OAUTH2_SIGNUP_CONTEXT_INVALID다")
    void translatesProviderUserConstraintViolation() {
        // given
        givenSignupPossible();
        given(userRepository.save(any(User.class))).willThrow(violation("uq_users_provider_user"));

        // when & then
        assertErrorCode(catchThrowable(
                () -> service.signup(TOKEN, NICKNAME)), UserErrorCode.OAUTH2_SIGNUP_CONTEXT_INVALID);
        then(signupContextRepository).should().delete(TOKEN);
    }

    @Test
    @DisplayName("저장 중 uq_users_nickname_lower 위반은 컨텍스트를 소비하지 않고 DUPLICATE_NICKNAME이다")
    void translatesNicknameConstraintViolation() {
        // given
        givenSignupPossible();
        given(userRepository.save(any(User.class))).willThrow(violation("uq_users_nickname_lower"));

        // when & then
        assertErrorCode(catchThrowable(
                () -> service.signup(TOKEN, NICKNAME)), UserErrorCode.DUPLICATE_NICKNAME);
        then(signupContextRepository).should(never()).delete(anyString());
    }

    @Test
    @DisplayName("모르는 무결성 위반은 중복으로 숨기지 않고 그대로 던지며 컨텍스트를 소비하지 않는다")
    void rethrowsUnknownIntegrityViolation() {
        // given
        givenSignupPossible();
        DataIntegrityViolationException failure = violation("ck_users_social_identifier");
        given(userRepository.save(any(User.class))).willThrow(failure);

        // when & then
        assertThatThrownBy(() -> service.signup(TOKEN, NICKNAME)).isSameAs(failure);
        then(signupContextRepository).should(never()).delete(anyString());
    }

    @Test
    @DisplayName("컨텍스트 소비가 실패해도 이미 저장된 가입 결과를 바꾸지 않는다")
    void ignoresConsumeFailureAfterSave() {
        // given
        givenSignupPossible();
        given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
        willThrow(new RedisConnectionFailureException("down")).given(signupContextRepository).delete(TOKEN);

        // when & then
        assertThat(service.signup(TOKEN, NICKNAME).email()).isEqualTo(EMAIL);
    }
}
