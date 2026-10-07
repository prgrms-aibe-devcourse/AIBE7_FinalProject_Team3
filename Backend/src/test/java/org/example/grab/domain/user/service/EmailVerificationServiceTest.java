package org.example.grab.domain.user.service;

import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.service.AsyncEmailDispatcher;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.EmailSignupContextRepository;
import org.example.grab.domain.user.repository.EmailVerificationCodeRepository;
import org.example.grab.domain.user.repository.EmailVerificationSendLimitRepository;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.domain.user.support.EmailSignupTokenGenerator;
import org.example.grab.domain.user.support.EmailVerificationCodeGenerator;
import org.example.grab.domain.user.support.EmailVerificationHasher;
import org.example.grab.domain.user.support.EmailVerificationMailFactory;
import org.example.grab.domain.user.support.EmailVerificationProperties;
import org.example.grab.domain.user.support.EmailVerificationSendLimitProperties;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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

// GR-61 M03-01·M03-03: 요청·확인의 순서와 분기. 저장소·발송기·생성기는 mock, 메일 내용과 코드 해시는 실제 객체로 만든다
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class EmailVerificationServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String IP = "203.0.113.7";
    private static final String CODE = "048213";
    private static final String WRONG_CODE = "048214";
    private static final String SIGNUP_TOKEN = "Xk3_vQ9aT1mZ0bR7cY2wL5nP8sD4fH6jK1gE3uA9qWo";
    private static final EmailVerificationHasher HASHER = new EmailVerificationHasher(new EmailVerificationProperties(
            Base64.getEncoder().encodeToString("grab-test-only-email-verification-secret".getBytes(StandardCharsets.UTF_8))));

    @Mock
    private EmailVerificationSendLimitRepository sendLimitRepository;
    @Mock
    private EmailVerificationCodeRepository codeRepository;
    @Mock
    private EmailVerificationCodeGenerator codeGenerator;
    @Mock
    private AsyncEmailDispatcher emailDispatcher;
    @Mock
    private UserRepository userRepository;
    @Mock
    private EmailSignupTokenGenerator signupTokenGenerator;
    @Mock
    private EmailSignupContextRepository signupContextRepository;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        EmailVerificationSendLimitProperties properties = new EmailVerificationSendLimitProperties(
                Duration.ofSeconds(60), 10, Duration.ofHours(24), 30, Duration.ofHours(1));
        service = new EmailVerificationService(sendLimitRepository, properties, codeRepository, codeGenerator,
                new EmailVerificationMailFactory(), emailDispatcher, HASHER, userRepository, signupTokenGenerator,
                signupContextRepository);
    }

    @Nested
    @DisplayName("코드 요청")
    class RequestCode {

        private void givenWithinLimits() {
            given(sendLimitRepository.incrementIpRequests(IP)).willReturn(30L);
            given(sendLimitRepository.tryStartResendInterval(EMAIL)).willReturn(true);
            given(sendLimitRepository.incrementEmailRequests(EMAIL)).willReturn(10L);
        }

        @Test
        @DisplayName("한도 안이면 IP → 재발송 간격 → 이메일 확인 후 코드를 저장하고, 저장 뒤에 그 코드의 메일을 발송한다")
        void savesCodeThenDispatchesMail(CapturedOutput output) {
            // given: 각 한도의 마지막 허용 값(30회째, 10회째)
            givenWithinLimits();
            given(codeGenerator.generate()).willReturn(CODE);

            // when
            service.requestCode(EMAIL, IP);

            // then
            InOrder order = inOrder(sendLimitRepository, codeRepository, emailDispatcher);
            order.verify(sendLimitRepository).incrementIpRequests(IP);
            order.verify(sendLimitRepository).tryStartResendInterval(EMAIL);
            order.verify(sendLimitRepository).incrementEmailRequests(EMAIL);
            order.verify(codeRepository).save(EMAIL, CODE);
            ArgumentCaptor<EmailMessage> message = ArgumentCaptor.forClass(EmailMessage.class);
            order.verify(emailDispatcher).dispatch(message.capture());
            assertThat(message.getValue().to()).isEqualTo(EMAIL);
            assertThat(message.getValue().textBody()).contains(CODE);
            assertThat(message.getValue().subject()).doesNotContain(CODE);
            assertThat(output).doesNotContain("인증 코드 요청 거부");
        }

        @Test
        @DisplayName("IP 한도를 넘으면 거부하고, 이메일의 재발송 간격·횟수를 건드리지 않으며 저장·발송하지 않는다")
        void rejectsWhenIpLimitExceeded(CapturedOutput output) {
            // given
            given(sendLimitRepository.incrementIpRequests(IP)).willReturn(31L);

            // when & then
            assertResendTooSoon();
            assertRejectReasonLogged(output, "IP_LIMIT");
            then(sendLimitRepository).should(never()).tryStartResendInterval(anyString());
            then(sendLimitRepository).should(never()).incrementEmailRequests(anyString());
            assertNothingSavedOrSent();
        }

        @Test
        @DisplayName("재발송 간격 안이면 거부하고, 이메일 횟수를 소모하지 않으며 저장·발송하지 않는다")
        void rejectsWithinResendInterval(CapturedOutput output) {
            // given
            given(sendLimitRepository.incrementIpRequests(IP)).willReturn(1L);
            given(sendLimitRepository.tryStartResendInterval(EMAIL)).willReturn(false);

            // when & then
            assertResendTooSoon();
            assertRejectReasonLogged(output, "RESEND_INTERVAL");
            then(sendLimitRepository).should(never()).incrementEmailRequests(anyString());
            assertNothingSavedOrSent();
        }

        @Test
        @DisplayName("이메일 한도를 넘으면 거부하고 저장·발송하지 않는다")
        void rejectsWhenEmailLimitExceeded(CapturedOutput output) {
            // given
            given(sendLimitRepository.incrementIpRequests(IP)).willReturn(1L);
            given(sendLimitRepository.tryStartResendInterval(EMAIL)).willReturn(true);
            given(sendLimitRepository.incrementEmailRequests(EMAIL)).willReturn(11L);

            // when & then
            assertResendTooSoon();
            assertRejectReasonLogged(output, "EMAIL_LIMIT");
            assertNothingSavedOrSent();
        }

        @Test
        @DisplayName("코드 저장이 실패하면 Redis 예외를 그대로 전파하고 메일을 보내지 않는다")
        void doesNotDispatchWhenSaveFails() {
            // given
            givenWithinLimits();
            given(codeGenerator.generate()).willReturn(CODE);
            RedisConnectionFailureException failure = new RedisConnectionFailureException("connection refused");
            willThrow(failure).given(codeRepository).save(EMAIL, CODE);

            // when & then
            assertThatThrownBy(() -> service.requestCode(EMAIL, IP)).isSameAs(failure);
            then(emailDispatcher).should(never()).dispatch(any());
        }

        private void assertResendTooSoon() {
            assertThatThrownBy(() -> service.requestCode(EMAIL, IP))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(UserErrorCode.EMAIL_VERIFICATION_RESEND_TOO_SOON))
                    // 예외 메시지는 WARN 로그에 남으므로 이메일·IP 원문이 없어야 한다(M03-04)
                    .hasMessageNotContaining(EMAIL)
                    .hasMessageNotContaining(IP);
        }

        // 사유 한 줄만 남고 이메일·IP 원문은 로그에 없다(NFR-011)
        private void assertRejectReasonLogged(CapturedOutput output, String reason) {
            assertThat(output).contains("인증 코드 요청 거부: reason=" + reason)
                    .doesNotContain(EMAIL)
                    .doesNotContain(IP);
        }

        private void assertNothingSavedOrSent() {
            then(codeGenerator).should(never()).generate();
            then(codeRepository).should(never()).save(anyString(), anyString());
            then(emailDispatcher).should(never()).dispatch(any());
        }
    }

    @Nested
    @DisplayName("코드 확인")
    class ConfirmCode {

        private void givenStoredCode(long attempts) {
            given(codeRepository.findCodeHash(EMAIL)).willReturn(Optional.of(HASHER.hashVerificationCode(CODE)));
            given(codeRepository.incrementAttempts(EMAIL)).willReturn(attempts);
        }

        @Test
        @DisplayName("맞으면 횟수 증가 → 코드 삭제 → 가입 여부 확인 후 컨텍스트를 저장하고 토큰 원문을 돌려준다")
        void issuesSignupContextWhenCodeMatches() {
            // given: 4회 틀린 뒤 5번째 시도에 맞혀도 성공이다
            givenStoredCode(5L);
            given(codeRepository.delete(EMAIL)).willReturn(true);
            given(userRepository.existsByEmail(EMAIL)).willReturn(false);
            given(signupTokenGenerator.generate()).willReturn(SIGNUP_TOKEN);

            // when
            String token = service.confirmCode(EMAIL, CODE);

            // then
            assertThat(token).isEqualTo(SIGNUP_TOKEN);
            InOrder order = inOrder(codeRepository, userRepository, signupContextRepository);
            order.verify(codeRepository).findCodeHash(EMAIL);
            order.verify(codeRepository).incrementAttempts(EMAIL);
            order.verify(codeRepository).delete(EMAIL);
            order.verify(userRepository).existsByEmail(EMAIL);
            order.verify(signupContextRepository).save(SIGNUP_TOKEN, EMAIL);
        }

        @Test
        @DisplayName("저장된 코드가 없으면 EXPIRED이고 시도 횟수를 올리지 않는다")
        void rejectsWhenNoCode() {
            // given
            given(codeRepository.findCodeHash(EMAIL)).willReturn(Optional.empty());

            // when & then
            assertErrorCode(CODE, UserErrorCode.EMAIL_VERIFICATION_CODE_EXPIRED);
            then(codeRepository).should(never()).incrementAttempts(anyString());
            assertNoContextIssued();
        }

        @Test
        @DisplayName("틀리고 5회 미만이면 MISMATCH이고 코드를 지우지 않는다")
        void rejectsMismatchBeforeLimit() {
            // given
            givenStoredCode(4L);

            // when & then
            assertErrorCode(WRONG_CODE, UserErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH);
            then(codeRepository).should(never()).delete(anyString());
            assertNoContextIssued();
        }

        @Test
        @DisplayName("5회째 틀리면 코드를 지우고 ATTEMPTS_EXCEEDED다")
        void deletesCodeOnFifthMismatch() {
            // given
            givenStoredCode(5L);

            // when & then
            assertErrorCode(WRONG_CODE, UserErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
            then(codeRepository).should().delete(EMAIL);
            assertNoContextIssued();
        }

        @Test
        @DisplayName("6회째 이후는 맞는 코드여도 비교하지 않고 ATTEMPTS_EXCEEDED다")
        void rejectsWithoutComparingAfterLimit() {
            // given: 5회째 요청이 코드를 지우기 전에 들어온 동시 요청
            givenStoredCode(6L);

            // when & then
            assertErrorCode(CODE, UserErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
            then(codeRepository).should(never()).delete(anyString());
            assertNoContextIssued();
        }

        @Test
        @DisplayName("맞았지만 다른 요청이 먼저 코드를 지웠으면 EXPIRED이고 컨텍스트를 발급하지 않는다")
        void rejectsWhenCodeAlreadyConsumed() {
            // given
            givenStoredCode(1L);
            given(codeRepository.delete(EMAIL)).willReturn(false);

            // when & then
            assertErrorCode(CODE, UserErrorCode.EMAIL_VERIFICATION_CODE_EXPIRED);
            then(userRepository).should(never()).existsByEmail(anyString());
            assertNoContextIssued();
        }

        @Test
        @DisplayName("가입된 이메일이면 코드는 지운 뒤 DUPLICATE_EMAIL이고 컨텍스트를 발급하지 않는다")
        void rejectsRegisteredEmailAfterConsumingCode() {
            // given
            givenStoredCode(1L);
            given(codeRepository.delete(EMAIL)).willReturn(true);
            given(userRepository.existsByEmail(EMAIL)).willReturn(true);

            // when & then
            assertErrorCode(CODE, UserErrorCode.DUPLICATE_EMAIL);
            then(codeRepository).should().delete(EMAIL);
            assertNoContextIssued();
        }

        private void assertErrorCode(String code, UserErrorCode errorCode) {
            assertThatThrownBy(() -> service.confirmCode(EMAIL, code))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode))
                    // 예외 메시지는 WARN 로그에 남으므로 이메일·코드 원문이 없어야 한다(M03-04)
                    .hasMessageNotContaining(EMAIL)
                    .hasMessageNotContaining(code);
        }

        private void assertNoContextIssued() {
            then(signupTokenGenerator).should(never()).generate();
            then(signupContextRepository).should(never()).save(anyString(), anyString());
        }
    }
}
