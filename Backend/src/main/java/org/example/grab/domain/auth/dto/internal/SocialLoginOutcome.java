package org.example.grab.domain.auth.dto.internal;

/*
    소셜 로그인 콜백 판정 결과(MEMBER_AUTH 1.5). 둘 중 하나만 값이 있다.
    - loginResult: 기존 회원 로그인 결과. 인증 쿠키를 만든다
    - signupToken: 최초 소셜 인증의 가입 컨텍스트 토큰 원문. oauth2_signup_token 쿠키에 담는다
    거부(ACCOUNT_DISABLED·ACCOUNT_LINK_REQUIRED)는 결과가 아니라 BusinessException으로 끝난다.
    토큰 원문을 담으므로 응답 본문으로 직렬화하지 않는다.
 */
public record SocialLoginOutcome(
        LoginResult loginResult,
        String signupToken
) {

    public SocialLoginOutcome(LoginResult loginResult, String signupToken) {
        if ((loginResult == null) == (signupToken == null)) {
            throw new IllegalArgumentException("소셜 로그인 결과는 로그인 또는 가입 필요 중 하나여야 합니다.");
        }
        this.loginResult = loginResult;
        this.signupToken = signupToken;
    }

    public static SocialLoginOutcome loggedIn(LoginResult loginResult) {
        return new SocialLoginOutcome(loginResult, null);
    }

    public static SocialLoginOutcome signupRequired(String signupToken) {
        return new SocialLoginOutcome(null, signupToken);
    }

    public boolean isSignupRequired() {
        return signupToken != null;
    }

    // 가입 컨텍스트 토큰 원문이 로그·예외 메시지에 남지 않게 한다. LoginResult는 스스로 토큰을 가린다
    @Override
    public String toString() {
        return isSignupRequired()
                ? "SocialLoginOutcome[signupRequired, signupToken=masked]"
                : "SocialLoginOutcome[" + loginResult + "]";
    }
}
