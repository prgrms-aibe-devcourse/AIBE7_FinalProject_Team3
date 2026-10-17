package org.example.grab.domain.auth.support.oauth2;

import org.example.grab.domain.user.entity.AuthProvider;
import org.example.grab.domain.user.error.UserErrorCode;
import org.example.grab.global.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// GR-46 M02-01(M00-04): 두 제공자 모두 ID 토큰의 sub·email·email_verified만 쓰고, 검증된 이메일만 인정한다
class SocialProfileTest {

    private static final String SUB = "4021973658";
    private static final String EMAIL = "user@example.com";

    // OidcUserService가 검증을 마친 뒤 만드는 인증 결과와 같은 형태
    private static OAuth2AuthenticationToken token(String registrationId, Map<String, Object> extraClaims) {
        Map<String, Object> claims = new HashMap<>(Map.of("sub", SUB, "iss", "https://kauth.kakao.com"));
        claims.putAll(extraClaims);
        OidcIdToken idToken = new OidcIdToken("id.token.value", Instant.now(), Instant.now().plusSeconds(60), claims);
        return new OAuth2AuthenticationToken(new DefaultOidcUser(List.of(), idToken), List.of(), registrationId);
    }

    private static void assertRejected(OAuth2AuthenticationToken token, UserErrorCode code) {
        assertThatThrownBy(() -> SocialProfile.from(token))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @ParameterizedTest
    @CsvSource({"kakao, KAKAO", "google, GOOGLE"})
    @DisplayName("등록 ID를 가입 제공자로 바꾸고, sub를 식별자로, 검증된 이메일을 정규화해 담는다")
    void mapsVerifiedProfile(String registrationId, AuthProvider provider) {
        // when
        SocialProfile profile = SocialProfile.from(token(registrationId,
                Map.of("email", " User@Example.COM ", "email_verified", true)));

        // then
        assertThat(profile.provider()).isEqualTo(provider);
        assertThat(profile.providerUserId()).isEqualTo(SUB);
        assertThat(profile.email()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("이메일이 없거나 비어 있으면 OAUTH2_EMAIL_REQUIRED다")
    void requiresEmail() {
        assertRejected(token("kakao", Map.of("email_verified", true)), UserErrorCode.OAUTH2_EMAIL_REQUIRED);
        assertRejected(token("kakao", Map.of("email", "   ", "email_verified", true)), UserErrorCode.OAUTH2_EMAIL_REQUIRED);
    }

    @Test
    @DisplayName("email_verified가 없거나 false이면 OAUTH2_EMAIL_REQUIRED다")
    void requiresVerifiedEmail() {
        // 카카오가 email_verified를 보내지 않으면 이 경로가 된다(M00-04 확인 필요)
        assertRejected(token("kakao", Map.of("email", EMAIL)), UserErrorCode.OAUTH2_EMAIL_REQUIRED);
        assertRejected(token("google", Map.of("email", EMAIL, "email_verified", false)), UserErrorCode.OAUTH2_EMAIL_REQUIRED);
    }

    @Test
    @DisplayName("등록되지 않은 제공자이거나 OIDC 사용자가 아니면 OAUTH2_AUTHENTICATION_FAILED다")
    void rejectsUnknownProviderOrNonOidcUser() {
        assertRejected(token("naver", Map.of("email", EMAIL, "email_verified", true)),
                UserErrorCode.OAUTH2_AUTHENTICATION_FAILED);

        DefaultOAuth2User plain = new DefaultOAuth2User(
                List.of(new OAuth2UserAuthority(Map.of("id", "1"))), Map.of("id", "1"), "id");
        assertRejected(new OAuth2AuthenticationToken(plain, List.of(), "kakao"), UserErrorCode.OAUTH2_AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("toString은 제공자 식별자와 이메일을 드러내지 않는다")
    void masksIdentifierAndEmail() {
        // given
        SocialProfile profile = new SocialProfile(AuthProvider.KAKAO, SUB, EMAIL);

        // when & then
        assertThat(profile.toString()).doesNotContain(SUB).doesNotContain(EMAIL).contains("KAKAO");
    }
}
