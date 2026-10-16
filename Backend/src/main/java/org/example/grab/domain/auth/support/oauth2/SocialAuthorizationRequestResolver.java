package org.example.grab.domain.auth.support.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/*
    소셜 로그인 시작 요청만 골라 인가 요청을 만든다(MEMBER_AUTH 1.4, GR-46 M00-02).
    기본 Resolver는 /api/v1/auth/oauth2/{registrationId}를 메서드와 관계없이 가로채고, 등록되지 않은 ID면 필터가 500으로 응답한다.
    그러면 POST /api/v1/auth/oauth2/signup이 registrationId "signup"으로 해석돼 Controller에 닿지 못한다.
    GET이면서 등록된 제공자(kakao·google)일 때만 기본 Resolver에 맡기고, 그 밖의 요청은 null을 돌려 다음 필터·Controller로 넘긴다.
    미지원 제공자의 GET은 SocialLoginController가 400 UNSUPPORTED_OAUTH2_PROVIDER로 응답한다.
    state·nonce·PKCE(S256) 생성은 기본 Resolver가 한다. PKCE는 ClientSettings.requireProofKey 기본값(true)으로 붙는다(M00-03).
 */
public class SocialAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String REGISTRATION_ID = "registrationId";

    // 요청이 GET이고 /api/v1/auth/oauth2/{registrationId} 형태인지 검사하는 RequestMatcher
    private final RequestMatcher startRequestMatcher = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, SocialProviders.AUTHORIZATION_BASE_URI + "/{" + REGISTRATION_ID + "}");
    // 인가 요청 생성을 맡길 Spring 기본 Resolver -> 제공자를 확인하고 카카오, 구글로 보낼 인가 요청 정보 생성하는 역할
    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    // resolver에 clientRegistrationRepository, AUTHORIZATION_BASE_URI 정보를 알려주고 생성자로 주입
    public SocialAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, SocialProviders.AUTHORIZATION_BASE_URI);
    }

    // 들어온 GET /api/v1/auth/oauth2/kakao와 같은 요청에 대해 형태가 맞는지 검사 후
    // 맞지 않으면 null, 맞으면 registrationId를 꺼내 아래 resolve 메서드를 호출
    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        RequestMatcher.MatchResult result = startRequestMatcher.matcher(request);
        if (!result.isMatch()) {
            return null;
        }
        return resolve(request, result.getVariables().get(REGISTRATION_ID));
    }

    // 제공자 확인과 GET 요청인지 확인 후 확인되었다면 로그인 주소, 스코프, state, nonce 등을 담은 OAuth2AuthorizationRequest를 반환
    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        if (!HttpMethod.GET.matches(request.getMethod()) || !SocialProviders.isSupported(clientRegistrationId)) {
            return null;
        }
        return delegate.resolve(request, clientRegistrationId);
    }
}
