package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.user.dto.request.SignupRequest;
import org.example.grab.domain.user.dto.response.SignupResponse;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.EmailSignupContextRepository;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.global.error.BusinessException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/*
    LOCAL 회원가입 완료(MEMBER_AUTH.md 1.2.3). 이메일은 요청이 아니라 가입 컨텍스트(GR-61)에 저장된 인증된 이메일을 쓴다.
    서비스 트랜잭션을 두지 않는다(GR-29 M00-02). 회원 저장은 save() 한 번으로 커밋되고, 사전 중복 검사는 친절한 오류용이라
    같은 트랜잭션일 필요가 없다. 동시 가입은 DB 유니크 제약이 최종으로 막는다. Argon2 해시도 DB 연결을 잡지 않은 채 계산한다.
    비밀번호 원문·해시와 가입 컨텍스트 토큰은 로그와 예외 메시지에 남기지 않는다(NFR-001, NFR-011).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignupService {

    // V1·V6 마이그레이션의 유니크 제약 이름
    private static final String EMAIL_UNIQUE_CONSTRAINT = "uq_users_email";
    private static final String NICKNAME_UNIQUE_CONSTRAINT = "uq_users_nickname_lower";

    private final EmailSignupContextRepository signupContextRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /*
        요청 본문 검증보다 가입 컨텍스트 확인이 먼저 진행되어야 한다.
        Controller가 본문 검증 결과를 보기 전에 호출한다.
        컨텍스트를 소비하지 않는다. signup()이 다시 확인하므로 그 사이에 만료되거나 소비돼도 결과는 같다.
     */
    public void requireSignupContext(String signupToken) {
        if (signupContextRepository.findEmail(signupToken).isEmpty()) {
            throw new BusinessException(UserErrorCode.EMAIL_SIGNUP_CONTEXT_INVALID);
        }
    }

    /*
        signupToken은 email_signup_token 쿠키 값이고, request는 Controller에서 검증을 마친 값이다(GR-30).
        - 이메일 중복을 닉네임보다 먼저 본다(GR-29 M00-03). 이메일이 중복이면 닉네임을 바꿔도 가입할 수 없다
        - 이메일 중복은 컨텍스트를 소비하고, 닉네임 중복은 소비하지 않아 같은 컨텍스트로 다시 요청할 수 있다
        - 컨텍스트는 회원이 저장된 뒤에 소비한다
        - 저장에서 유니크 제약에 걸리면 사전 검사와 같은 규칙으로 응답한다(saveUser)
     */
    public SignupResponse signup(String signupToken, SignupRequest request) {
        String email = signupContextRepository.findEmail(signupToken)
                .orElseThrow(() -> new BusinessException(UserErrorCode.EMAIL_SIGNUP_CONTEXT_INVALID));

        if (userRepository.existsByEmail(email)) {
            consumeSignupContext(signupToken);
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
        }
        if (userRepository.existsByNicknameIgnoreCase(request.nickname())) {
            throw new BusinessException(UserErrorCode.DUPLICATE_NICKNAME);
        }

        // 비밀번호는 trim·가공하지 않고 그대로 해시한다(MEMBER_AUTH.md 1.2.3)
        String passwordHash = passwordEncoder.encode(request.password());
        User user = saveUser(signupToken, User.createLocal(email, passwordHash, request.nickname()));
        consumeSignupContext(signupToken);
        return SignupResponse.from(user);
    }

    /*
        동시 가입은 사전 검사를 함께 통과할 수 있어 저장에서 유니크 제약에 걸린다. 사전 검사와 같은 응답이 되도록 바꾼다.
        제약 이름으로만 구분하고, 다른 무결성 위반은 중복으로 숨기지 않고 그대로 던진다(GR-29 M03-02).
        원래 예외에는 SQL과 입력값이 담기므로 BusinessException에 원인으로 연결하지 않는다.
     */
    private User saveUser(String signupToken, User user) {
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, EMAIL_UNIQUE_CONSTRAINT)) {
                consumeSignupContext(signupToken);
                throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
            }
            if (violates(e, NICKNAME_UNIQUE_CONSTRAINT)) {
                throw new BusinessException(UserErrorCode.DUPLICATE_NICKNAME);
            }
            throw e;
        }
    }

    // Hibernate가 DB 오류에서 꺼낸 제약 이름을 비교한다
    private static boolean violates(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            // DB가 오류를 반환하면, Hibernate가 그 오류를 ConstraintViolationException으로 변환함
            if (cause instanceof ConstraintViolationException violation) {
                // 발생한 db 제약 문자열이 constraintName과 같다면 true 반환
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    /*
        컨텍스트 소비가 실패해도 결과를 바꾸지 않는다(GR-29 M00-02). 회원이 이미 저장됐는데 500을 주면 클라이언트는 가입 실패로 본다.
        남은 컨텍스트는 15분 뒤 만료되고, 다시 쓰여도 이메일 중복으로 끝나 계정이 더 생기지 않는다.
        로그에는 토큰을 넣지 않고 예외 타입 이름만 남긴다.
     */
    private void consumeSignupContext(String signupToken) {
        try {
            signupContextRepository.delete(signupToken);
        } catch (DataAccessException e) {
            log.warn("[SignupService.consumeSignupContext]가입 컨텍스트 소비 실패: {}", e.getClass().getSimpleName());
        }
    }
}
