package org.example.grab.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.example.grab.domain.auth.dto.internal.LoginResult;
import org.example.grab.domain.auth.dto.internal.SocialLoginOutcome;
import org.example.grab.domain.auth.support.oauth2.SocialProfile;
import org.example.grab.domain.user.dto.internal.LoginUser;
import org.example.grab.domain.user.service.SocialAccountService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/*
    소셜 로그인 콜백 판정(MEMBER_AUTH.md 1.5, GR-46 M00-05)과 소셜 가입 완료(1.6)의 세션 발급.
    ① 제공자 식별자로 기존 회원 → 상태 확인(ACTIVE 아니면 ACCOUNT_DISABLED) 후 로그인
    ② 없으면 같은 정규화 이메일 회원 → ACCOUNT_LINK_REQUIRED
    ③ 없으면 가입 컨텍스트 발급(회원은 만들지 않는다)
    제공자 인증(state·ID 토큰·nonce)과 이메일 검증(SocialProfile)이 성공한 뒤에만 부른다.
    세션 발급은 LOCAL 로그인과 같은 AuthSessionIssuer가 한다(M00-08).
 */
@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final SocialAccountService socialAccountService;
    private final AuthSessionIssuer authSessionIssuer;

    public SocialLoginOutcome login(SocialProfile profile) {
        // 제공된 profile로 기존 회원을 찾음
        Optional<LoginUser> user = socialAccountService.findLoginUser(profile.provider(), profile.providerUserId());
        // GRAB에 저장된 회원이 있다면 GRAB 토큰(access, refresh)을 발급 후 로그인 성공 결과로 반환
        if (user.isPresent()) {
            return SocialLoginOutcome.loggedIn(authSessionIssuer.issue(user.get()));
        }
        // 해당 메일로 GRAB에 가입된 유저가 있는지 검증
        socialAccountService.requireEmailNotTaken(profile.email());

        // 신규 사용자에게 가입 컨텍스트 발급 후 가입 필요 결과 반환
        return SocialLoginOutcome.signupRequired(socialAccountService.issueSignupContext(
                profile.provider(), profile.providerUserId(), profile.email()));
    }

    // 소셜 가입 완료(1.6)에서 요청 본문 검증보다 먼저 부른다. 컨텍스트를 소비하지 않는다
    public void requireSignupContext(String signupToken) {
        socialAccountService.requireSignupContext(signupToken);
    }

    /*
        소셜 가입 완료(MEMBER_AUTH.md 1.6). 회원을 만든 뒤 LOCAL 로그인과 같은 방식으로 세션을 발급한다.
        발급(Redis)이 실패하면 500이고 쿠키는 나가지 않지만 회원은 남는다.
        이후 같은 계정으로 다시 소셜 로그인하면 기존 회원으로 로그인되므로 따로 되돌리지 않는다.
     */
    public LoginResult signup(String signupToken, String nickname) {
        return authSessionIssuer.issue(socialAccountService.signup(signupToken, nickname));
    }
}
