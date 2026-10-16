package org.example.grab.domain.user.service;

import lombok.RequiredArgsConstructor;
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
import org.springframework.stereotype.Service;

import java.util.Optional;

/*
    소셜 회원 조회와 가입 컨텍스트 발급(MEMBER_AUTH.md 1.5, GR-46 M00-05·06). 회원 엔티티는 user 도메인 밖으로 내보내지 않고 LoginUser만 넘긴다.
    - 기존 회원은 이메일이 아니라 제공자 식별자로 찾는다. 제공자 쪽 이메일이 바뀌어도 저장된 이메일은 갱신하지 않는다
    - 로그인·거부 모두 기존 회원 데이터를 바꾸지 않는다
    서비스 트랜잭션을 두지 않는다. 조회는 각자 끝나고, Redis 저장은 DB 트랜잭션과 묶이지 않는다.
    이메일·제공자 식별자·토큰은 로그와 예외 메시지에 남기지 않는다(NFR-010).
 */
@Service
@RequiredArgsConstructor
public class SocialAccountService {

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
}
