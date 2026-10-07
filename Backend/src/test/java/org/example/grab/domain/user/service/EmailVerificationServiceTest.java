package org.example.grab.domain.user.service;

import org.example.grab.domain.mail.dto.EmailMessage;
import org.example.grab.domain.mail.service.AsyncEmailDispatcher;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.EmailVerificationCodeRepository;
import org.example.grab.domain.user.repository.EmailVerificationSendLimitRepository;
import org.example.grab.domain.user.support.EmailVerificationCodeGenerator;
import org.example.grab.domain.user.support.EmailVerificationMailFactory;
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
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

// GR-61 M03-01: 발송 제한·저장·발송 순서와 분기. 저장소·발송기는 mock, 메일 내용은 실제 팩토리로 만든다
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String IP = "203.0.113.7";
    private static final String CODE = "048213";

    @Mock
    private EmailVerificationSendLimitRepository sendLimitRepository;
    @Mock
    private EmailVerificationCodeRepository codeRepository;
    @Mock
    private EmailVerificationCodeGenerator codeGenerator;
    @Mock
    private AsyncEmailDispatcher emailDispatcher;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        EmailVerificationSendLimitProperties properties = new EmailVerificationSendLimitProperties(
                Duration.ofSeconds(60), 10, Duration.ofHours(24), 30, Duration.ofHours(1));
        service = new EmailVerificationService(sendLimitRepository, properties, codeRepository, codeGenerator,
                new EmailVerificationMailFactory(), emailDispatcher);
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
        void savesCodeThenDispatchesMail() {
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
        }

        @Test
        @DisplayName("IP 한도를 넘으면 거부하고, 이메일의 재발송 간격·횟수를 건드리지 않으며 저장·발송하지 않는다")
        void rejectsWhenIpLimitExceeded() {
            // given
            given(sendLimitRepository.incrementIpRequests(IP)).willReturn(31L);

            // when & then
            assertResendTooSoon();
            then(sendLimitRepository).should(never()).tryStartResendInterval(anyString());
            then(sendLimitRepository).should(never()).incrementEmailRequests(anyString());
            assertNothingSavedOrSent();
        }

        @Test
        @DisplayName("재발송 간격 안이면 거부하고, 이메일 횟수를 소모하지 않으며 저장·발송하지 않는다")
        void rejectsWithinResendInterval() {
            // given
            given(sendLimitRepository.incrementIpRequests(IP)).willReturn(1L);
            given(sendLimitRepository.tryStartResendInterval(EMAIL)).willReturn(false);

            // when & then
            assertResendTooSoon();
            then(sendLimitRepository).should(never()).incrementEmailRequests(anyString());
            assertNothingSavedOrSent();
        }

        @Test
        @DisplayName("이메일 한도를 넘으면 거부하고 저장·발송하지 않는다")
        void rejectsWhenEmailLimitExceeded() {
            // given
            given(sendLimitRepository.incrementIpRequests(IP)).willReturn(1L);
            given(sendLimitRepository.tryStartResendInterval(EMAIL)).willReturn(true);
            given(sendLimitRepository.incrementEmailRequests(EMAIL)).willReturn(11L);

            // when & then
            assertResendTooSoon();
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

        private void assertNothingSavedOrSent() {
            then(codeGenerator).should(never()).generate();
            then(codeRepository).should(never()).save(anyString(), anyString());
            then(emailDispatcher).should(never()).dispatch(any());
        }
    }
}
