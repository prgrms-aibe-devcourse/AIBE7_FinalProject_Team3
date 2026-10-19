package org.example.grab.domain.auth.support.oauth2;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/*
    소셜 로그인 설정(GR-46 M00-07·09). Client ID·Secret은 저장소에 두지 않고 환경변수로만 주입하며 기본값이 없다.
    값이 없으면 기동하지 않는다(JWT_SECRET과 같음). 테스트는 src/test/resources/config/application.yml의 테스트 값을 쓴다.
    frontendCallbackUrl은 콜백 결과를 받을 프론트엔드 주소다. 요청에서 반환 주소를 받지 않아 다른 사이트로 보내지 않는다(MEMBER_AUTH 1.4).
    toString()은 Secret을 가린다.
 */
@ConfigurationProperties(prefix = "grab.auth.oauth2")
public record SocialLoginProperties(
        String frontendCallbackUrl,
        Client kakao,
        Client google
) {

    public SocialLoginProperties(String frontendCallbackUrl, Client kakao, Client google) {
        if (frontendCallbackUrl == null || frontendCallbackUrl.isBlank()) {
            throw new IllegalArgumentException("grab.auth.oauth2.frontend-callback-url은 필수입니다.");
        }
        URI uri = URI.create(frontendCallbackUrl);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new IllegalArgumentException("grab.auth.oauth2.frontend-callback-url은 http 또는 https 절대 주소여야 합니다.");
        }
        if (kakao == null || google == null) {
            throw new IllegalArgumentException("grab.auth.oauth2.kakao·google 설정은 필수입니다.");
        }
        this.frontendCallbackUrl = frontendCallbackUrl;
        this.kakao = kakao;
        this.google = google;
    }

    public record Client(String clientId, String clientSecret) {

        public Client(String clientId, String clientSecret) {
            if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
                throw new IllegalArgumentException("소셜 로그인 client-id·client-secret은 필수입니다.");
            }
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        @Override
        public String toString() {
            return "Client[clientId=" + clientId + ", clientSecret=masked]";
        }
    }
}
