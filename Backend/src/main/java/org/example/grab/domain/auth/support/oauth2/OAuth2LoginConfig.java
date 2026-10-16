package org.example.grab.domain.auth.support.oauth2;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/*
    KAKAO·GOOGLE OpenID Connect Client 등록(MEMBER_AUTH 1.4·1.5, GR-46 M01-02, M00-04·09).
    - 제공자 주소를 코드로 고정해 등록한다. issuer-uri 설정은 기동할 때 제공자에 discovery를 요청하므로 쓰지 않는다
      (제공자 장애·오프라인 CI에서 앱이 뜨지 않는다). 카카오 주소는 2026-10-15 discovery로 확인한 값이다
    - issuerUri를 함께 넣어 ID 토큰의 iss를 검증하게 한다. 서명(RS256·JWKS)·aud·exp·nonce 검증은 Spring 표준 그대로다
    - 사용자 정보 엔드포인트를 두지 않아 ID 토큰의 sub·email·email_verified만 쓴다. 제공자 프로필은 받지 않는다
    Client ID·Secret은 SocialLoginProperties로 환경변수에서만 받는다.
 */
@Configuration
@EnableConfigurationProperties(SocialLoginProperties.class)
public class OAuth2LoginConfig {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(SocialLoginProperties properties) {
        return new InMemoryClientRegistrationRepository(kakao(properties.kakao()), google(properties.google()));
    }

    // 카카오 OIDC(콘솔에서 OpenID Connect 사용 설정 필요). 토큰 요청은 client_secret_post
    private static ClientRegistration kakao(SocialLoginProperties.Client client) {
        return ClientRegistration.withRegistrationId(SocialProviders.KAKAO)
                .clientId(client.clientId())
                .clientSecret(client.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(SocialProviders.REDIRECT_URI_TEMPLATE)
                .scope("openid", "account_email")
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .jwkSetUri("https://kauth.kakao.com/.well-known/jwks.json")
                .issuerUri("https://kauth.kakao.com")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName("Kakao")
                .build();
    }

    // 구글 OIDC. 주소·issuer는 Spring의 GOOGLE 기본값을 쓰고, 스코프·콜백 주소만 바꾸고 사용자 정보 엔드포인트는 뺀다
    private static ClientRegistration google(SocialLoginProperties.Client client) {
        return CommonOAuth2Provider.GOOGLE.getBuilder(SocialProviders.GOOGLE)
                .clientId(client.clientId())
                .clientSecret(client.clientSecret())
                .redirectUri(SocialProviders.REDIRECT_URI_TEMPLATE)
                .scope("openid", "email")
                .userInfoUri(null)
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .build();
    }
}
