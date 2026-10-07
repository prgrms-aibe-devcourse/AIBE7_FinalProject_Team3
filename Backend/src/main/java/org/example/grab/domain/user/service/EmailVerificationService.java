package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
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
import org.example.grab.domain.user.support.EmailVerificationSendLimitProperties;
import org.example.grab.global.error.BusinessException;
import org.springframework.stereotype.Service;

/*
    이메일 인증 코드 요청·확인(GR-61 M03). 데이터는 Redis에 있고 DB는 가입 여부 조회 한 번뿐이라 트랜잭션을 두지 않는다.
    코드·토큰·이메일 원문은 로그와 예외 메시지에 남기지 않는다(NFR-011). 예외는 오류 코드의 기본 문구만 쓴다.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    // MEMBER_AUTH 1.2: 코드 하나당 5회까지 틀릴 수 있다. 명세 고정값이라 설정으로 두지 않는다
    static final int MAX_CONFIRM_ATTEMPTS = 5;

    private final EmailVerificationSendLimitRepository sendLimitRepository;
    private final EmailVerificationSendLimitProperties sendLimitProperties;
    private final EmailVerificationCodeRepository codeRepository;
    private final EmailVerificationCodeGenerator codeGenerator;
    private final EmailVerificationMailFactory mailFactory;
    private final AsyncEmailDispatcher emailDispatcher;
    private final EmailVerificationHasher hasher;
    private final UserRepository userRepository;
    private final EmailSignupTokenGenerator signupTokenGenerator;
    private final EmailSignupContextRepository signupContextRepository;

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
        인증 코드를 확인하고, 맞으면 가입 컨텍스트를 발급해 토큰 원문을 돌려준다(MEMBER_AUTH 1.2.2). 컨트롤러가 쿠키에 담는다(M04-01).
        - 비교 전에 시도 횟수를 올린다. 동시에 틀린 코드를 많이 보내도 INCR이 요청마다 다른 값을 주므로 실제로 비교되는 요청은 5개뿐이다
        - 코드 키를 이 요청이 지웠을 때만 성공이다. 같은 코드로 동시에 확인해도 한 요청만 컨텍스트를 받는다(코드 1회 사용)
        - 가입 여부는 코드가 맞아 이메일 소유가 확인된 뒤에만 알린다. 이때도 코드는 이미 폐기됐다
        성공한 시도도 횟수에 들어가지만 성공하면 코드가 지워지므로 "5회째 틀리면 폐기" 규칙과 결과가 같다.
        code 형식(숫자 6자리)은 요청 DTO에서 이미 검증되어, 형식 오류는 시도 횟수에 들어가지 않는다.
     */
    public String confirmCode(String email, String code) {
        // redis에 해당 email을 키 값으로 저장되고 있는 코드가 있는지 확인 후 반환
        String storedHash = codeRepository.findCodeHash(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.EMAIL_VERIFICATION_CODE_EXPIRED));

        long attempts = codeRepository.incrementAttempts(email);
        // 다른 요청이 5회째로 코드를 지우기 전에 들어온 6회째 이후 요청이다. 비교하지 않는다
        if (attempts > MAX_CONFIRM_ATTEMPTS) {
            throw new BusinessException(UserErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
        }

        if (!hasher.matchesVerificationCode(code, storedHash)) {
            if (attempts == MAX_CONFIRM_ATTEMPTS) {
                codeRepository.delete(email);
                throw new BusinessException(UserErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
            }
            throw new BusinessException(UserErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH);
        }

        // 조회 뒤 만료됐거나 같은 코드로 먼저 성공한 요청이 지웠다. 유효한 코드가 없는 것과 같다
        if (!codeRepository.delete(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_VERIFICATION_CODE_EXPIRED);
        }

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
        }

        String signupToken = signupTokenGenerator.generate();
        signupContextRepository.save(signupToken, email);
        return signupToken;
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
