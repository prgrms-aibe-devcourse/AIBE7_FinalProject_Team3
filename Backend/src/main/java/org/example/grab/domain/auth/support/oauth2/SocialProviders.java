package org.example.grab.domain.auth.support.oauth2;

/*
    소셜 로그인 등록 ID와 콜백 주소(MEMBER_AUTH 1.4·1.5, GR-46 M00-02).
    등록 ID는 경로의 provider 값(소문자 kakao·google)과 같다.
 */
public final class SocialProviders {

    public static final String KAKAO = "kakao";
    public static final String GOOGLE = "google";

    // 제공자 콘솔에 등록하는 Redirect URI와 같아야 한다. {baseUrl}은 요청 기준 백엔드 주소다
    public static final String REDIRECT_URI_TEMPLATE = "{baseUrl}/api/v1/auth/oauth2/{registrationId}/callback";

    private SocialProviders() {
    }
}
