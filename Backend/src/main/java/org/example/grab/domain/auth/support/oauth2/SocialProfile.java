package org.example.grab.domain.auth.support.oauth2;

import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.domain.user.support.EmailNormalizer;
import org.example.grab.global.error.BusinessException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/*
    제공자 인증 결과에서 콜백 판정에 쓰는 값만 꺼낸다(MEMBER_AUTH 1.5, GR-46 M00-04).
    두 제공자 모두 OIDC이므로 OidcUser의 sub와 email·email_verified를 같은 방식으로 읽는다. 프로필(이름·사진)은 쓰지 않는다.
    GOOGLE은 ID 토큰의 값이고, KAKAO는 ID 토큰에 email_verified가 없어 OIDC userinfo의 값이다(M00-04, OAuth2LoginConfig).
    이메일은 email_verified가 true일 때만 인정하고 1.2절 규칙(EmailNormalizer)으로 정규화한다.
    toString()은 식별자·이메일을 가린다(NFR-010).
 */
public record SocialProfile(
        AuthProvider provider,
        String providerUserId,
        String email
) {

    /*
        - 등록되지 않은 제공자, OIDC가 아닌 사용자, sub 없음: OAUTH2_AUTHENTICATION_FAILED
        - 이메일 없음·미검증: OAUTH2_EMAIL_REQUIRED
     */
    public static SocialProfile from(OAuth2AuthenticationToken authentication) {
        // 인증에 사용된 provider 설정에서 registrationId 문자열을 꺼내고 AuthProvider enum 타입으로 변환
        AuthProvider provider = SocialProviders.toProvider(authentication.getAuthorizedClientRegistrationId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.OAUTH2_AUTHENTICATION_FAILED));

        // 미지원 제공자, OIDC 사용자 X, sub 없으면 에러 던짐
        if (!(authentication.getPrincipal() instanceof OidcUser user)
                || user.getSubject() == null || user.getSubject().isBlank()) {
            throw new BusinessException(UserErrorCode.OAUTH2_AUTHENTICATION_FAILED);
        }

        // 이메일 정규화
        String email = EmailNormalizer.normalize(user.getEmail());

        // 이메일이 없거나 getEmailVerified()가 false일경우 OAUTH2_EMAIL_REQUIRED
        if (email == null || email.isEmpty() || !Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new BusinessException(UserErrorCode.OAUTH2_EMAIL_REQUIRED);
        }
        return new SocialProfile(provider, user.getSubject(), email);
    }

    @Override
    public String toString() {
        return "SocialProfile[provider=" + provider + ", providerUserId=masked, email=masked]";
    }
}
