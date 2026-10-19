package org.example.grab.domain.auth.support.oauth2;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/*
    KAKAO·GOOGLE OpenID Connect Client 등록(MEMBER_AUTH 1.4·1.5, GR-46 M01-02, M00-04·09).
    - issuerUri를 함께 넣어 ID 토큰의 iss를 검증하게 한다. 서명(RS256·JWKS)·aud·exp·nonce 검증은 Spring 표준 그대로다
    - GOOGLE은 사용자 정보 엔드포인트를 두지 않아 ID 토큰의 sub·email·email_verified만 쓴다.
      KAKAO는 ID 토큰에 email_verified가 없어 OIDC userinfo에서 email·email_verified를 받는다(GR-46 M03-04). 제공자 프로필은 받지 않는다
    Client ID·Secret은 SocialLoginProperties로 환경변수에서만 받는다.
    SecurityConfig(global)는 도메인을 참조하지 않으므로 oauth2Login 설정을 Customizer 빈으로 넘긴다(GR-46 M01-03).
 */
@Configuration
@EnableConfigurationProperties(SocialLoginProperties.class)
public class OAuth2LoginConfig {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(SocialLoginProperties properties) {
        // 카카오와 구글 각각 제공자 하나의 로그인 설정인 ClientRegistration 생성
        // 두 설정을 메모리에 보관하는 저장소를 만들고 빈으로 등록
        // Spring Security가 카카오, 구글 로그인에 사용할 설정을 담는 표준 객체 -> ClientRegistration
        return new InMemoryClientRegistrationRepository(kakao(properties.kakao()), google(properties.google()));
    }

    /*
        시작 GET /api/v1/auth/oauth2/{provider}, 콜백 GET /api/v1/auth/oauth2/{provider}/callback(M00-02).
        인가 요청(state·nonce)은 로그인 왕복 동안 서버 세션에 둔다. SecurityContext는 세션에 저장하지 않는다(M00-03, STATELESS 유지).
     */
    @Bean
    Customizer<OAuth2LoginConfigurer<HttpSecurity>> oauth2LoginCustomizer(
            ClientRegistrationRepository clientRegistrationRepository, // 카카오, 구글 주소, Client ID, Secret 등을 보관한 저장소
            SocialLoginSuccessHandler successHandler, // 제공자 인증 성공 후 실행할 코드
            SocialLoginFailureHandler failureHandler // 제공자 인증 실패 후 실행할 코드
    ) {
        return oauth2 -> oauth2
                .clientRegistrationRepository(clientRegistrationRepository) // 카카오, 구글 설정을 clientRegistrationRepository에서 가져온다
                // 인증 과정에서 받은 카카오, 구글 토큰을 이후 사용을 위해 저장하지 않도록 지정
                .authorizedClientRepository(new DiscardingOAuth2AuthorizedClientRepository())
                // 기본 로그인 페이지(/login)를 만들지 않게 한다. 이 경로가 실제로 화면을 응답하지는 않는다
                .loginPage(SocialProviders.AUTHORIZATION_BASE_URI)
                // loginPage를 지정하면 콜백 처리 경로 기본값이 바뀔 수 있어 함께 명시한다
                .loginProcessingUrl(SocialProviders.CALLBACK_URI_PATTERN)
                .authorizationEndpoint(endpoint -> endpoint
                        .baseUri(SocialProviders.AUTHORIZATION_BASE_URI) // 로그인 시작 경로를 지정
                        // GET의 kakao, google 요청만 골라 인가 요청 생성
                        .authorizationRequestResolver(new SocialAuthorizationRequestResolver(clientRegistrationRepository))
                        // 생성한 인가 요청 정보를 로그인 왕복 동안 서버 세션에 저장
                        .authorizationRequestRepository(new HttpSessionOAuth2AuthorizationRequestRepository()))
                .redirectionEndpoint(endpoint -> endpoint.baseUri(SocialProviders.CALLBACK_URI_PATTERN)) // 콜백 경로 지정
                .successHandler(successHandler)
                .failureHandler(failureHandler);
    }

    // 카카오 OIDC(콘솔에서 OpenID Connect 사용 설정 필요). 토큰 요청은 client_secret_post
    private static ClientRegistration kakao(SocialLoginProperties.Client client) {
        return ClientRegistration.withRegistrationId(SocialProviders.KAKAO) // 이 이름으로 설정을 구분
                .clientId(client.clientId()) // 카카오에 등록한 GRAB 앱의 식별자
                .clientSecret(client.clientSecret()) // 토큰 요청 시 GRAB 앱을 인증하는 비밀값
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST) // 토큰 요청 시 POST 본문에 client id, secret을 담아 인증
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE) // 사용자가 로그인한 뒤 받은 인가 코드를 백엔드가 토큰으로 교환
                .redirectUri(SocialProviders.REDIRECT_URI_TEMPLATE) // 카카오 인증 후 브라우저가 돌아올 백엔드 주소
                .scope("openid", "account_email") // scope -> 카카오에 요청할 범위, openid: OpenID Connect 인증 요청, account_email: 이메일 정보 요청
                .authorizationUri("https://kauth.kakao.com/oauth/authorize") // 카카오 로그인 화면의 주소
                .tokenUri("https://kauth.kakao.com/oauth/token") // 백엔드가 인가 코드를 토큰으로 교환
                .jwkSetUri("https://kauth.kakao.com/.well-known/jwks.json") // ID 토큰 서명 검증에 사용할 공개키 조회
                // ID 토큰에 email_verified가 없어 userinfo에서 받는다. Spring이 userinfo의 sub가 ID 토큰과 같은지 확인한다
                .userInfoUri("https://kapi.kakao.com/v1/oidc/userinfo")
                .issuerUri("https://kauth.kakao.com") // ID 토큰이 기대한 발급자에게서 왔는지 확인할 때 사용되는 주소
                .userNameAttributeName(IdTokenClaimNames.SUB) // ID 토큰 안의 카카오 사용자 고유 식별자를 사용자 식별 기준으로 사용하도록 지정
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
