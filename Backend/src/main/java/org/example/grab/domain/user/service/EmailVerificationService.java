package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.mail.service.AsyncEmailDispatcher;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.EmailVerificationCodeRepository;
import org.example.grab.domain.user.repository.EmailVerificationSendLimitRepository;
import org.example.grab.domain.user.support.EmailVerificationCodeGenerator;
import org.example.grab.domain.user.support.EmailVerificationMailFactory;
import org.example.grab.domain.user.support.EmailVerificationSendLimitProperties;
import org.example.grab.global.error.BusinessException;
import org.springframework.stereotype.Service;

/*
    이메일 인증 코드 요청·확인(GR-61 M03). 데이터는 모두 Redis에만 있어 DB 트랜잭션을 쓰지 않는다.
    코드·이메일 원문은 로그와 예외 메시지에 남기지 않는다(NFR-011). 예외는 오류 코드의 기본 문구만 쓴다.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final EmailVerificationSendLimitRepository sendLimitRepository;
    private final EmailVerificationSendLimitProperties sendLimitProperties;
    private final EmailVerificationCodeRepository codeRepository;
    private final EmailVerificationCodeGenerator codeGenerator;
    private final EmailVerificationMailFactory mailFactory;
    private final AsyncEmailDispatcher emailDispatcher;

    /*
        인증 코드를 새로 만들어 저장하고 메일 발송을 맡긴다(MEMBER_AUTH 1.2.1).
        - 가입 여부를 조회하지 않는다. 가입된 이메일도 같은 흐름·같은 응답이어야 응답으로 가입 여부를 알 수 없다
        - 저장이 성공한 뒤에만 발송한다(M00-02). 저장이 실패하면 Redis 예외가 전파되어 쓸 수 없는 코드가 메일로 나가지 않는다
        - dispatch()는 발송을 기다리지 않고 발송 실패도 던지지 않으므로, 발송 결과가 응답을 바꾸지 않는다
        email은 정규화한 값, ipAddress는 컨트롤러가 request.getRemoteAddr()로 구한 값이다(M00-04).
     */
    public void requestCode(String email, String ipAddress) {
        checkSendLimit(email, ipAddress);

        String code = codeGenerator.generate();
        codeRepository.save(email, code);
        emailDispatcher.dispatch(mailFactory.create(email, code));
    }

    /*
        IP 한도 → 재발송 간격 → 이메일 한도 순서로 확인한다(M02-02). 거부는 모두 EMAIL_VERIFICATION_RESEND_TOO_SOON이다.
        - IP를 먼저 봐서 IP 한도에 걸린 요청이 남의 이메일에 재발송 간격을 걸거나 이메일 횟수를 소모하지 못하게 한다
        - 재발송 간격에 걸린 요청은 이메일 횟수를 소모하지 않는다
        뒤 단계에서 거부·실패해도 앞에서 올린 값은 되돌리지 않는다. 제한이 더 엄격해지는 쪽이고 시간이 지나면 풀린다.
     */
    private void checkSendLimit(String email, String ipAddress) {
        if (sendLimitRepository.incrementIpRequests(ipAddress) > sendLimitProperties.ipMaxRequests()) {
            throw resendTooSoon();
        }
        if (!sendLimitRepository.tryStartResendInterval(email)) {
            throw resendTooSoon();
        }
        if (sendLimitRepository.incrementEmailRequests(email) > sendLimitProperties.emailMaxRequests()) {
            throw resendTooSoon();
        }
    }

    private static BusinessException resendTooSoon() {
        return new BusinessException(UserErrorCode.EMAIL_VERIFICATION_RESEND_TOO_SOON);
    }
}
