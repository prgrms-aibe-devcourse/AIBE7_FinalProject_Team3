package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.entity.User;
import org.example.grab.domain.user.entity.UserStatus;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.repository.OAuth2SignupContext;
import org.example.grab.domain.user.repository.OAuth2SignupContextRepository;
import org.example.grab.domain.user.repository.UserRepository;
import org.example.grab.domain.user.support.SignupTokenGenerator;
import org.example.grab.global.error.BusinessException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/*
    소셜 회원 조회·가입 컨텍스트 발급·가입 완료(MEMBER_AUTH.md 1.5·1.6, GR-46 M00-05·06). 회원 엔티티는 user 도메인 밖으로 내보내지 않고 LoginUser만 넘긴다.
    - 기존 회원은 이메일이 아니라 제공자 식별자로 찾는다. 제공자 쪽 이메일이 바뀌어도 저장된 이메일은 갱신하지 않는다
    - 로그인·거부 모두 기존 회원 데이터를 바꾸지 않는다
    - 가입 컨텍스트는 성공·DUPLICATE_EMAIL에서만 소비하고, 닉네임·요청 값 오류는 소비하지 않는다(M00-06)
    서비스 트랜잭션을 두지 않는다(SignupService와 같은 이유). 동시 가입의 최종 판단은 DB 유니크 제약이 한다.
    이메일·제공자 식별자·토큰은 로그와 예외 메시지에 남기지 않는다(NFR-010).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialAccountService {

    // V1·V6·V22 마이그레이션의 유니크 제약 이름
    private static final String EMAIL_UNIQUE_CONSTRAINT = "uq_users_email";
    private static final String NICKNAME_UNIQUE_CONSTRAINT = "uq_users_nickname_lower";
    private static final String PROVIDER_USER_UNIQUE_CONSTRAINT = "uq_users_provider_user";

    private final UserRepository userRepository;
    private final OAuth2SignupContextRepository signupContextRepository;
    private final SignupTokenGenerator signupTokenGenerator;

    /*
        콜백 판정 1. 제공자 인증이 성공한 뒤에만 부른다(GR-34 인계).
        회원이 없으면 비어 있고, ACTIVE가 아니면 ACCOUNT_DISABLED다.
     */
    public Optional<LoginUser> findLoginUser(AuthProvider provider, String providerUserId) {
        Optional<User> user = userRepository.findByProviderAndProviderUserId(provider, providerUserId);
        // USER가 있는데 ACTIVE가 아니면 에러 던짐
        if (user.isPresent() && user.get().getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }
        // user가 있으면 LoginUser로 변환 후 반환, 없으면 Optional.empty() 반환
        return user.map(LoginUser::from);
    }

    // 콜백 판정 2. LOCAL·다른 제공자·같은 제공자의 다른 식별자 모두 같은 이메일이면 자동으로 연결하지 않는다(AUTH-008)
    // 즉, 같은 이메일로 가입된 회원이 있으면 예외 던짐
    public void requireEmailNotTaken(String normalizedEmail) {
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(UserErrorCode.ACCOUNT_LINK_REQUIRED);
        }
    }

    // 콜백 판정 3. 회원은 만들지 않고 가입 컨텍스트만 저장한 뒤 쿠키에 담을 토큰 원문을 돌려준다(AUTH-007)
    public String issueSignupContext(AuthProvider provider, String providerUserId, String normalizedEmail) {
        String rawToken = signupTokenGenerator.generate();
        signupContextRepository.save(rawToken, new OAuth2SignupContext(provider, providerUserId, normalizedEmail));
        return rawToken;
    }

    /*
        요청 본문 검증보다 가입 컨텍스트 확인이 먼저다(MEMBER_AUTH.md 1.6). Controller가 본문 검증 결과를 보기 전에 호출한다.
        소비하지 않는다. signup()이 다시 확인하므로 그 사이에 만료되거나 소비돼도 결과는 같다.
     */
    public void requireSignupContext(String signupToken) {
        if (signupContextRepository.find(signupToken).isEmpty()) {
            throw new BusinessException(UserErrorCode.OAUTH2_SIGNUP_CONTEXT_INVALID);
        }
    }

    /*
        소셜 가입 완료(MEMBER_AUTH.md 1.6). nickname은 Controller에서 검증을 마친 값이다.
        제공자·식별자·이메일은 컨텍스트 값만 쓴다. USER·ACTIVE, password_hash 없이 만든다(User.createSocial).
        이메일 중복을 닉네임보다 먼저 본다. 이메일이 중복이면 닉네임을 바꿔도 가입할 수 없어 컨텍스트를 소비한다.
        컨텍스트는 회원이 저장된 뒤에 소비한다.
     */
    public LoginUser signup(String signupToken, String nickname) {
        // 가입 토큰으로 Redis의 가입 컨텍스트 조회
        OAuth2SignupContext context = signupContextRepository.find(signupToken)
                .orElseThrow(() -> new BusinessException(UserErrorCode.OAUTH2_SIGNUP_CONTEXT_INVALID));

        if (userRepository.existsByEmail(context.email())) {
            consumeSignupContext(signupToken);
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
        }
        if (userRepository.existsByNicknameIgnoreCase(nickname)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_NICKNAME);
        }

        // User.createSocial 로 회원 객체 생성 후 DB 저장
        User user = saveUser(signupToken, User.createSocial(context.email(), context.provider(), context.providerUserId(), nickname));
        // 사용한 가입 컨텍스트 삭제
        consumeSignupContext(signupToken);
        log.info("소셜 회원가입 완료: userId={}, provider={}", user.getUuid(), user.getProvider());
        // 생성 회원을 LoginUser로 변환해 반환
        return LoginUser.from(user);
    }

    /*
        동시 요청은 사전 검사를 함께 통과할 수 있어 저장에서 유니크 제약에 걸린다. 사전 검사와 같은 응답으로 바꾼다.
        같은 컨텍스트의 이중 제출로 제공자 식별자가 겹치면 컨텍스트가 이미 쓰인 것이므로 OAUTH2_SIGNUP_CONTEXT_INVALID다(M00-06).
        제약 이름으로만 구분하고, 다른 무결성 위반은 그대로 던진다. 원래 예외에는 SQL과 입력값이 담기므로 원인으로 연결하지 않는다.
     */
    private User saveUser(String signupToken, User user) {
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, EMAIL_UNIQUE_CONSTRAINT)) {
                consumeSignupContext(signupToken);
                throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
            }
            if (violates(e, PROVIDER_USER_UNIQUE_CONSTRAINT)) {
                consumeSignupContext(signupToken);
                throw new BusinessException(UserErrorCode.OAUTH2_SIGNUP_CONTEXT_INVALID);
            }
            if (violates(e, NICKNAME_UNIQUE_CONSTRAINT)) {
                throw new BusinessException(UserErrorCode.DUPLICATE_NICKNAME);
            }
            throw e;
        }
    }

    // Hibernate가 DB 오류에서 꺼낸 제약 이름을 비교한다(SignupService와 같음)
    private static boolean violates(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    // 소비 실패는 결과를 바꾸지 않는다. 남은 컨텍스트는 15분 뒤 만료되고, 다시 쓰여도 이메일 중복으로 끝난다(SignupService와 같음)
    private void consumeSignupContext(String signupToken) {
        try {
            signupContextRepository.delete(signupToken);
        } catch (DataAccessException e) {
            log.warn("소셜 가입 컨텍스트 소비 실패: {}", e.getClass().getSimpleName());
        }
    }
}
