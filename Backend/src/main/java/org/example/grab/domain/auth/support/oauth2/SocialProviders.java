package org.example.grab.domain.auth.support.oauth2;

import org.example.grab.domain.user.entity.AuthProvider;

import java.util.Map;
import java.util.Optional;

/*
    소셜 로그인 등록 ID와 경로(MEMBER_AUTH 1.4·1.5, GR-46 M00-02).
    등록 ID는 경로의 provider 값(소문자 kakao·google)과 같고, 회원에는 KAKAO·GOOGLE로 저장한다.
 */
public final class SocialProviders {

    public static final String KAKAO = "kakao";
    public static final String GOOGLE = "google";

    // 소셜 로그인 시작 경로는 {AUTHORIZATION_BASE_URI}/{provider}, 콜백은 {AUTHORIZATION_BASE_URI}/{provider}/callback이다
    public static final String AUTHORIZATION_BASE_URI = "/api/v1/auth/oauth2";
    public static final String CALLBACK_URI_PATTERN = AUTHORIZATION_BASE_URI + "/*/callback";

    // 제공자 콘솔에 등록하는 Redirect URI와 같아야 한다. {baseUrl}은 요청 기준 백엔드 주소다
    public static final String REDIRECT_URI_TEMPLATE = "{baseUrl}" + AUTHORIZATION_BASE_URI + "/{registrationId}/callback";

    // 등록 ID와 회원에 저장하는 가입 제공자(users.provider)의 대응
    private static final Map<String, AuthProvider> PROVIDERS = Map.of(
            KAKAO, AuthProvider.KAKAO,
            GOOGLE, AuthProvider.GOOGLE
    );

    private SocialProviders() {
    }

    public static boolean isSupported(String registrationId) {
        return registrationId != null && PROVIDERS.containsKey(registrationId);
    }

    public static Optional<AuthProvider> toProvider(String registrationId) {
        return registrationId == null ? Optional.empty() : Optional.ofNullable(PROVIDERS.get(registrationId));
    }
}
